package com.icegps.voiceime.service

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * 转写记录器：保存语音文件（AAC/M4A）+ JSONL 转写日志到共享存储，供 Syncthing 同步到 PC。
 *
 * 目录结构（与 Windows 版对齐）：
 *   /sdcard/VoiceIME/recordings/2026/10/2026-10-13/153022.m4a
 *   /sdcard/VoiceIME/logs/2026/10/2026-10-13.jsonl
 *
 * JSONL 每行：
 *   {"ts":"2026-10-13T15:30:22","audio":"recordings/2026/10/2026-10-13/153022.m4a",
 *    "text":"识别结果","engine":"ondevice","dur_ms":3200}
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

            // 1. 保存 AAC (M4A)
            val recDir = File(baseDir(), "recordings/$dp").apply { mkdirs() }
            val aacFile = File(recDir, "$timeStr.m4a")
            writeAac(aacFile, pcm)

            // 2. Append JSONL
            val logDir = File(baseDir(), "logs/$dp").apply { mkdirs() }
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            val jsonlFile = File(logDir, "$dateStr.jsonl")
            val entry = JSONObject().apply {
                put("ts", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(now))
                put("audio", "recordings/$dp/$timeStr.m4a")
                put("text", text)
                put("engine", engine)
                put("dur_ms", durMs)
            }
            FileOutputStream(jsonlFile, true).use { out ->
                out.write((entry.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
            Log.i(TAG, "已保存转写记录: ${jsonlFile.name} + ${aacFile.name}")
        } catch (e: Exception) {
            Log.w(TAG, "保存转写记录失败", e)
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
