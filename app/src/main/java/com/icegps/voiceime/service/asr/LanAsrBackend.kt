package com.icegps.voiceime.service.asr

import android.os.SystemClock
import com.icegps.voiceime.service.ServerClient
import java.io.IOException

/**
 * 局域网后端：把 V1 的 [ServerClient] 逻辑封装成 [AsrBackend]。
 *
 * 用途：
 *  - 设置里转写引擎选"仅局域网"时使用
 *  - 端侧模型未就绪时的自动降级通道
 */
class LanAsrBackend(
    private val host: String,
    private val port: Int,
) : AsrBackend {

    override val name: String = "lan"

    override fun isAvailable(): Boolean = host.isNotBlank()

    @Throws(Exception::class)
    override fun transcribe(pcm16k: ShortArray): AsrResult {
        val t0 = SystemClock.elapsedRealtime()
        val floats = ServerClient.int16ToFloat32(pcm16k)
        val timeStart = System.currentTimeMillis() / 1000.0
        val text = when (val r = ServerClient.transcribe(host, port, floats, timeStart)) {
            is ServerClient.TranscribeResult.Ok -> r.text.trim()
            is ServerClient.TranscribeResult.Err -> throw IOException("局域网转写失败：${r.msg}")
        }
        val latencyMs = SystemClock.elapsedRealtime() - t0
        logTranscribeLatency(name, latencyMs, text.length, "host=$host:$port")
        return AsrResult(text, latencyMs)
    }
}
