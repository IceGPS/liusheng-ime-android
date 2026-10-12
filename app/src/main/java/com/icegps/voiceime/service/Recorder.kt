package com.icegps.voiceime.service

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 录音器：AudioRecord 封装。
 *
 * - 16kHz 单声道 PCM_16BIT（与服务端要求的 16kHz mono 对应，
 *   Windows 端是 48kHz 降采样，这里直接采 16kHz，省去重采样）
 * - 按住时 start()，松开时 stopAndGetPcm() 拿到全部 int16 数据
 * - 调用方负责转 float32（ServerClient.int16ToFloat32）再发往服务端
 */
class Recorder {

    companion object {
        const val SAMPLE_RATE = 16000
    }

    private var record: AudioRecord? = null
    private var readThread: Thread? = null
    @Volatile private var recording = false

    private val chunks = mutableListOf<ShortArray>()
    private var totalShorts = 0

    /**
     * 开始录音。需要 RECORD_AUDIO 权限（调用前确保已授予）。
     * @return 是否成功启动
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        synchronized(chunks) { chunks.clear() }
        totalShorts = 0
        recording = true
        record = rec
        rec.startRecording()

        readThread = Thread({
            val buf = ShortArray(minBuf)
            while (recording) {
                val n = try {
                    rec.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    break
                }
                if (n > 0) {
                    synchronized(chunks) {
                        chunks.add(buf.copyOf(n))
                        totalShorts += n
                    }
                } else if (n < 0) {
                    break // ERROR_INVALID_OPERATION 等
                }
                // n == 0：继续轮询
            }
        }, "voice-ime-recorder").also { it.start() }
        return true
    }

    /** 停止录音，返回全部 PCM 数据（int16）。 */
    fun stopAndGetPcm(): ShortArray {
        recording = false
        try {
            readThread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        readThread = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        record = null

        val out = ShortArray(totalShorts)
        var pos = 0
        synchronized(chunks) {
            for (c in chunks) {
                c.copyInto(out, pos)
                pos += c.size
            }
            chunks.clear()
        }
        totalShorts = 0
        return out
    }

    fun isRecording(): Boolean = recording

    /** 录音时长（秒），按已采集的采样数估算 */
    fun durationSeconds(): Float = totalShorts / SAMPLE_RATE.toFloat()
}
