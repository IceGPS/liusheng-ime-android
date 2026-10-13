package com.icegps.voiceime.service

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 转写记录器：保存语音文件 + JSONL 转写日志到共享存储，供 Syncthing 同步到 PC。
 *
 * 目录结构（与 Windows 版对齐）：
 *   /sdcard/VoiceIME/recordings/2026/10/2026-10-13/153022.wav
 *   /sdcard/VoiceIME/logs/2026/10/2026-10-13.jsonl
 *
 * JSONL 每行：
 *   {"ts":"2026-10-13T15:30:22","audio":"recordings/2026/10/2026-10-13/153022.wav",
 *    "text":"识别结果","engine":"ondevice","dur_ms":3200}
 */
object TranscriptLogger {
    private const val TAG = "TranscriptLogger"
    private const val ROOT_DIR = "VoiceIME"
    private const val SAMPLE_RATE = 16000

    fun baseDir(): File {
        return File(
            Environment.getExternalStorageDirectory(),
            ROOT_DIR
        )
    }

    private fun datePath(date: Date): String {
        val y = SimpleDateFormat("yyyy", Locale.US).format(date)
        val m = SimpleDateFormat("MM", Locale.US).format(date)
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        return "$y/$m/$d"
    }

    /**
     * 保存一次转写：写 WAV + append JSONL。
     * @param pcm 16kHz 16-bit mono PCM
     * @param text 识别结果
     * @param engine "ondevice" 或 "lan"
     * @param durMs 录音时长毫秒
     */
    fun log(ctx: Context, pcm: ShortArray, text: String, engine: String, durMs: Long) {
        try {
            val now = Date()
            val dp = datePath(now)
            val timeStr = SimpleDateFormat("HHmmss", Locale.US).format(now)

            // 1. 保存 WAV
            val recDir = File(baseDir(), "recordings/$dp").apply { mkdirs() }
            val wavFile = File(recDir, "$timeStr.wav")
            writeWav(wavFile, pcm)

            // 2. Append JSONL
            val logDir = File(baseDir(), "logs/$dp").apply { mkdirs() }
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            val jsonlFile = File(logDir, "$dateStr.jsonl")
            val entry = JSONObject().apply {
                put("ts", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(now))
                put("audio", "recordings/$dp/$timeStr.wav")
                put("text", text)
                put("engine", engine)
                put("dur_ms", durMs)
            }
            FileOutputStream(jsonlFile, true).use { out ->
                out.write((entry.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
            Log.i(TAG, "已保存转写记录: ${jsonlFile.name} + ${wavFile.name}")
        } catch (e: Exception) {
            Log.w(TAG, "保存转写记录失败", e)
        }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val dataSize = pcm.size * 2
        val totalSize = 36 + dataSize
        FileOutputStream(file).use { out ->
            // RIFF header
            out.write("RIFF".toByteArray())
            out.write(intToBytes(totalSize))
            out.write("WAVE".toByteArray())
            // fmt chunk
            out.write("fmt ".toByteArray())
            out.write(intToBytes(16))
            out.write(shortToBytes(1)) // PCM
            out.write(shortToBytes(1)) // mono
            out.write(intToBytes(SAMPLE_RATE))
            out.write(intToBytes(SAMPLE_RATE * 2)) // byte rate
            out.write(shortToBytes(2)) // block align
            out.write(shortToBytes(16)) // bits per sample
            // data chunk
            out.write("data".toByteArray())
            out.write(intToBytes(dataSize))
            val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) buf.putShort(s)
            out.write(buf.array())
        }
    }

    private fun intToBytes(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

    private fun shortToBytes(v: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
}
