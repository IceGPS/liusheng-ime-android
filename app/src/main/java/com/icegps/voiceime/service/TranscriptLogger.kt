package com.icegps.voiceime.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import com.icegps.voiceime.util.Prefs
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import org.json.JSONObject

/**
 * 转写记录器：保存语音文件（AAC/M4A）+ JSONL 转写日志到共享存储，供 Syncthing 同步到 PC。
 *
 * 目录结构（与 Windows 版对齐）：
 *   /sdcard/VoiceIME/recordings/2026/10/2026-10-13/153022.m4a
 *   /sdcard/VoiceIME/logs/2026/10/2026-10-13.jsonl
 *
 * JSONL schema v2（V71 起）：
 *   {"v":2,"uuid":"...","device_id":"...","ts":"2026-10-20T00:35:00+08:00",
 *    "status":"ok","audio":"recordings/2026/10/2026-10-20/003500.m4a",
 *    "text":"识别结果","engine":"ondevice","dur_ms":3200}
 *   status: ok=识别出文本 / empty=无语音或空结果 / error=失败（此时有 error 字段说明原因）
 *
 * 读兼容：v1 记录（缺 v/uuid/device_id/status，ts 无时区）仍可读取，
 * 缺失字段按空/未知处理；v2 保留 v1 全部字段名与语义，只做加法。
 *
 * AAC 编码：Android MediaCodec（系统原生），iPhone AVFoundation 也支持，通用。
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
     * 保存一次转写：写 AAC + append JSONL（schema v2）。
     * 记录**所有**识别尝试（成功/空结果/失败），不再只存成功非空。
     * 文件名规则（对标 CapsWriter）：(20261013-153022)文本前15字.m4a
     * @param pcm 16kHz 16-bit mono PCM（为空时不写音频文件，audio 字段为空字符串）
     * @param text 识别结果（empty/error 时可为空）
     * @param engine "ondevice" / "lan" / "unknown"（录音失败时未知）
     * @param durMs 录音时长毫秒
     * @param status "ok" | "empty" | "error"
     * @param error status=error 时的失败原因，可空
     */
    /** @return 写入记录的 uuid（V91 起供语音纠错事件关联）；失败返回 null。 */
    fun log(ctx: Context, pcm: ShortArray, text: String, engine: String, durMs: Long,
            status: String = "ok", error: String? = null): String? {
        var entryUuid: String? = null
        try {
            val now = Date()
            val dp = datePath(now)
            val timeStr = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now)

            // 1. 保存 AAC (M4A)；无 PCM 时跳过
            var audioRelPath = ""
            if (pcm.isNotEmpty()) {
                // 文件名：(时间戳)文本前15字，非法字符替换为空格
                val textClean = text.take(15).replace(Regex("[\\\\/:\"*?<>|]"), " ").trim()
                val fileName = if (textClean.isEmpty()) "($timeStr).m4a"
                               else "($timeStr)$textClean.m4a"
                val recDir = File(baseDir(), "recordings/$dp").apply { mkdirs() }
                val aacFile = File(recDir, fileName)
                writeAac(aacFile, pcm)
                audioRelPath = "recordings/$dp/$fileName"
            }

            // 2. Append JSONL（v2：ts 带时区）
            val logDir = File(baseDir(), "logs/$dp").apply { mkdirs() }
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            val jsonlFile = File(logDir, "$dateStr.jsonl")
            val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            entryUuid = UUID.randomUUID().toString()
            val entry = JSONObject().apply {
                put("v", 2)
                put("uuid", entryUuid)
                put("device_id", Prefs.getDeviceId(ctx))
                put("ts", tsFmt.format(now))
                put("status", status)
                put("audio", audioRelPath)
                put("text", text)
                put("engine", engine)
                put("dur_ms", durMs)
                if (error != null) put("error", error)
            }
            FileOutputStream(jsonlFile, true).use { out ->
                out.write((entry.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
            Log.i(TAG, "已保存转写记录[$status]: ${jsonlFile.name}")
        } catch (e: Exception) {
            Log.w(TAG, "保存转写记录失败", e)
            entryUuid = null
        }
        return entryUuid
    }

    /**
     * V91：写一条语音纠错事件（schema v2 加法扩展，type=correction），
     * 与转写记录同当日 JSONL 文件。编译器按 status!="ok" 将其排除在正文之外，
     * 由 doc_compiler 的纠错统计单独聚合。设计见 correction-learning-phase1-design.md。
     */
    fun logCorrection(ctx: Context, voiceId: String, before: String, after: String,
                      ctxL: String, ctxR: String, homophone: Boolean,
                      pinyinBefore: String, pinyinAfter: String) {
        try {
            val now = Date()
            val dp = datePath(now)
            val logDir = File(baseDir(), "logs/$dp").apply { mkdirs() }
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            val jsonlFile = File(logDir, "$dateStr.jsonl")
            val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            val entry = JSONObject().apply {
                put("v", 2)
                put("type", "correction")
                put("uuid", UUID.randomUUID().toString())
                put("device_id", Prefs.getDeviceId(ctx))
                put("ts", tsFmt.format(now))
                put("voice_id", voiceId)
                put("before", before)
                put("after", after)
                put("ctx_l", ctxL)
                put("ctx_r", ctxR)
                put("homophone", homophone)
                put("pinyin_before", pinyinBefore)
                put("pinyin_after", pinyinAfter)
            }
            FileOutputStream(jsonlFile, true).use { out ->
                out.write((entry.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
            Log.i(TAG, "已保存纠错记录: $before->$after (homophone=$homophone)")
        } catch (e: Exception) {
            Log.w(TAG, "保存纠错记录失败", e)
        }
    }

    /**
     * PCM (16kHz mono int16) → AAC (M4A)，用系统 MediaCodec，零第三方依赖。
     * AAC 是通用标准：Android MediaCodec / iPhone AVFoundation 都原生支持。
     */
    private fun writeAac(file: File, pcm: ShortArray) {
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        var muxer: MediaMuxer? = null
        var trackIndex = -1
        try {
            muxer = MediaMuxer(file.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // 输入 PCM
            val pcmBytes = ByteBuffer.allocate(pcm.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer().put(pcm)
            // 转换回 ByteBuffer
            val inputBuf = ByteBuffer.allocateDirect(pcm.size * 2)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            inputBuf.asShortBuffer().put(pcm)
            inputBuf.rewind()

            val chunkSize = 2048 // 每次喂 2048 shorts
            var offset = 0
            var ptsUs = 0L
            val bufferInfo = MediaCodec.BufferInfo()

            // 喂数据
            while (offset < pcm.size) {
                val len = minOf(chunkSize, pcm.size - offset)
                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)!!
                    inBuf.clear()
                    // 复制 len 个 shorts
                    val sb = inBuf.asShortBuffer()
                    sb.put(pcm, offset, len)
                    val bytesLen = len * 2
                    codec.queueInputBuffer(inIndex, 0, bytesLen,
                        ptsUs, 0)
                    ptsUs += (len * 1_000_000L / SAMPLE_RATE)
                    offset += len
                }
                // 取输出
                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                while (outIndex >= 0) {
                    if (trackIndex < 0) {
                        // 等待编码器输出 format
                    } else {
                        val outBuf = codec.getOutputBuffer(outIndex)!!
                        if (bufferInfo.size > 0) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                }
            }

            // 结束输入
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                codec.queueInputBuffer(inIndex, 0, 0, ptsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            // 取完剩余输出
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
            while (outIndex >= 0 || outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (bufferInfo.size > 0 && trackIndex >= 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED &&
                    trackIndex < 0) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
            }
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
        }
    }
}
