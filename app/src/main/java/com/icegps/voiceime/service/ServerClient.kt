package com.icegps.voiceime.service

import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.icegps.voiceime.util.Protocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 与 PC 服务端的 WebSocket 客户端。
 *
 * 协议照抄 Windows 客户端 core/client/connection/websocket_manager.py：
 *  - URL：ws://{host}:{port}
 *  - 子协议：binary（Sec-WebSocket-Protocol 头）
 *  - 发送：AudioMessage JSON（文本帧）；录音中分片发 is_final=false，
 *    结束时发空包 is_final=true（与 Windows 端 recorder.py 一致）
 *  - 接收：RecognitionMessage JSON（文本帧），等 is_final=true 取 text
 *
 * 用法（后台线程调用）：
 *   val r = ServerClient.transcribe(host, port, pcmFloat32, timeStart)
 *   r.onSuccess { text -> ... } / r.onFailure { err -> ... }
 */
object ServerClient {

    private const val CONNECT_TIMEOUT_S = 10L
    /** 等待最终结果的最长秒数（27B 级别的服务端也够用了，这里是 ASR，很快） */
    private const val RESULT_TIMEOUT_S = 120L
    /** 每次发送的音频片长（秒），与 Windows 端的分片思路一致 */
    private const val CHUNK_SECONDS = 1.0
    private const val SAMPLE_RATE = 16000

    sealed class TranscribeResult {
        data class Ok(val text: String) : TranscribeResult()
        data class Err(val msg: String) : TranscribeResult()
    }

    /**
     * 同步式转写：连接 -> 分片发送 -> 等待最终结果 -> 关闭。
     * 必须在后台线程调用（内部会阻塞等待）。
     */
    fun transcribe(
        host: String,
        port: Int,
        pcm: FloatArray,
        timeStart: Double,
    ): TranscribeResult {
        val taskId = UUID.randomUUID().toString()
        val connected = CountDownLatch(1)
        val done = CountDownLatch(1)
        val finalText = AtomicReference<String?>(null)
        val error = AtomicReference<String?>(null)
        val wsRef = AtomicReference<WebSocket?>(null)

        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            // 长连接：读超时设为 0（永不超时），靠应用层 done 信号结束
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("ws://$host:$port")
            // 与 Windows 端 websockets 库 subprotocols=["binary"] 对应
            .addHeader("Sec-WebSocket-Protocol", "binary")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsRef.set(webSocket)
                connected.countDown()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = Protocol.RecognitionMessage.fromJson(text) ?: return
                if (msg.taskId != taskId) return
                if (msg.isFinal) {
                    finalText.set(msg.text)
                    done.countDown()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (done.count > 0) {
                    error.compareAndSet(null, "连接失败: ${t.message}")
                    connected.countDown()
                    done.countDown()
                }
            }
        }

        val ws = client.newWebSocket(request, listener)
        try {
            // 等连接建立
            if (!connected.await(CONNECT_TIMEOUT_S + 5, TimeUnit.SECONDS) || wsRef.get() == null) {
                return TranscribeResult.Err(error.get() ?: "连接服务端超时（$host:$port）")
            }
            val socket = wsRef.get()!!

            // 分片发送音频（is_final=false）
            val chunkSize = (SAMPLE_RATE * CHUNK_SECONDS).toInt()
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + chunkSize, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)
                val msg = Protocol.AudioMessage(
                    taskId = taskId,
                    data = base64Float32(chunk),
                    isFinal = false,
                    timeStart = timeStart,
                )
                if (!socket.send(msg.toJson())) {
                    return TranscribeResult.Err("发送音频失败：连接已断开")
                }
                offset = end
            }
            // 结束包：空数据 + is_final=true（与 Windows 端一致）
            val fin = Protocol.AudioMessage(
                taskId = taskId, data = "", isFinal = true, timeStart = timeStart
            )
            socket.send(fin.toJson())

            // 等最终结果
            if (!done.await(RESULT_TIMEOUT_S, TimeUnit.SECONDS)) {
                return TranscribeResult.Err("等待识别结果超时")
            }
            val text = finalText.get()
            return if (text != null) TranscribeResult.Ok(text)
            else TranscribeResult.Err(error.get() ?: "未收到识别结果")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return TranscribeResult.Err("转写被中断")
        } finally {
            try { ws.close(1000, "done") } catch (_: Exception) {}
            client.dispatcher.executorService.shutdown()
        }
    }

    /** float32 PCM -> Base64（小端字节序，与 Windows 端 np.tobytes() 一致） */
    fun base64Float32(floats: FloatArray): String {
        val buf = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) buf.putFloat(f)
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP)
    }

    /** int16 PCM -> float32（除以 32768），AudioRecord 采集到的是 int16 */
    fun int16ToFloat32(pcm: ShortArray, len: Int = pcm.size): FloatArray {
        val out = FloatArray(len)
        for (i in 0 until len) out[i] = pcm[i] / 32768f
        return out
    }

    /**
     * 轻量连通性测试：只做 WebSocket 握手，不发送音频。
     * 必须在后台线程调用。
     */
    fun checkConnection(host: String, port: Int): Boolean {
        val opened = CountDownLatch(1)
        val ok = java.util.concurrent.atomic.AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("ws://$host:$port")
            .addHeader("Sec-WebSocket-Protocol", "binary")
            .build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ok.set(true)
                opened.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.countDown()
            }
        })
        return try {
            opened.await(CONNECT_TIMEOUT_S + 5, TimeUnit.SECONDS) && ok.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            try { ws.close(1000, "ping") } catch (_: Exception) {}
            client.dispatcher.executorService.shutdown()
        }
    }

    /** 在主线程执行回调 */
    fun onMain(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
