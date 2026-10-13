package com.icegps.voiceime.service.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * 端侧语音识别后端（V16）：sherpa-onnx + SenseVoice-Small INT8。
 *
 * 替代 MNN/Qwen3-ASR 方案的原因：
 *  - MNN Omni 引擎的 <audio> 标签解析在 CI 构建版本中不可用，导致 native 崩溃
 *  - SenseVoice-Small 仅 ~230MB，IME 进程内存预算（256-512MB）内可跑；
 *    Qwen3-ASR 0.6B 的 1.39GB 在输入法进程里必 OOM
 *  - sherpa-onnx 提供预编译 AAR + Kotlin API，无需手写 JNI
 *
 * 模型文件（<filesDir>/models/sense-voice-small/）：
 *  - model.int8.onnx（~230MB）
 *  - tokens.txt
 */
class SherpaAsrBackend(private val appCtx: Context) : AsrBackend {

    override val name: String = "sherpa"

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val initLock = Any()

    companion object {
        private const val TAG = "VoiceIME"
        const val MODEL_DIR_NAME = "sense-voice-small"
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"

        /** 模型根目录：<filesDir>/models/sense-voice-small/ */
        fun modelDir(ctx: Context): File = File(File(ctx.filesDir, "models"), MODEL_DIR_NAME)

        /** 备份目录：/sdcard/Download/voice-ime-models/sense-voice-small/（卸载不删除） */
        fun backupDir(): File = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "voice-ime-models/$MODEL_DIR_NAME"
        )

        /** 模型是否就绪：model.int8.onnx + tokens.txt 存在且大小合理（防止加载未下载完的文件） */
        fun isModelReady(ctx: Context): Boolean {
            val dir = modelDir(ctx)
            val modelFile = File(dir, MODEL_FILE)
            val tokensFile = File(dir, TOKENS_FILE)
            // model.int8.onnx 约 228MB，tokens.txt 约 308KB；小于阈值视为未下载完
            return modelFile.isFile && modelFile.length() > 200L * 1024 * 1024 &&
                   tokensFile.isFile && tokensFile.length() > 100L * 1024
        }

        /**
         * 从备份恢复模型（卸载重装后免重新下载）。
         * @return 成功恢复返回 true
         */
        fun restoreFromBackup(ctx: Context): Boolean {
            if (isModelReady(ctx)) return true
            val backup = backupDir()
            val modelBak = File(backup, MODEL_FILE)
            val tokensBak = File(backup, TOKENS_FILE)
            if (!modelBak.isFile || modelBak.length() < 200L * 1024 * 1024) return false
            if (!tokensBak.isFile || tokensBak.length() < 100L * 1024) return false
            return try {
                val dir = modelDir(ctx).apply { mkdirs() }
                modelBak.copyTo(File(dir, MODEL_FILE), overwrite = true)
                tokensBak.copyTo(File(dir, TOKENS_FILE), overwrite = true)
                Log.i(TAG, "[sherpa] 模型已从备份恢复，无需重新下载")
                true
            } catch (e: Exception) {
                Log.w(TAG, "[sherpa] 从备份恢复失败", e)
                false
            }
        }

        /**
         * 备份模型到 Download（下载完成后调用）。
         */
        fun backupModel(ctx: Context) {
            if (!isModelReady(ctx)) return
            try {
                val dir = modelDir(ctx)
                val backup = backupDir().apply { mkdirs() }
                File(dir, MODEL_FILE).copyTo(File(backup, MODEL_FILE), overwrite = true)
                File(dir, TOKENS_FILE).copyTo(File(backup, TOKENS_FILE), overwrite = true)
                Log.i(TAG, "[sherpa] 模型已备份到 ${backup.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "[sherpa] 模型备份失败", e)
            }
        }
    }

    override fun isAvailable(): Boolean {
        // 先尝试从备份恢复（卸载重装场景）
        if (!isModelReady(appCtx)) {
            restoreFromBackup(appCtx)
        }
        return isModelReady(appCtx)
    }

    @Throws(Exception::class)
    private fun ensureInit(): OfflineRecognizer {
        synchronized(initLock) {
            recognizer?.let { return it }
            if (!isModelReady(appCtx)) throw IllegalStateException("端侧模型文件未就绪")
            val dir = modelDir(appCtx)
            Log.i(TAG, "[sherpa] loading model from ${dir.absolutePath} ...")
            val t0 = SystemClock.elapsedRealtime()

            // 用 setter 方式构造，避免 Kotlin 命名参数与 AAR 版本不一致
            val senseVoiceConfig = OfflineSenseVoiceModelConfig().apply {
                model = File(dir, MODEL_FILE).absolutePath
                language = "auto"
                useInverseTextNormalization = true
            }
            val modelCfg = OfflineModelConfig().apply {
                senseVoice = senseVoiceConfig
                tokens = File(dir, TOKENS_FILE).absolutePath
                numThreads = 4
                provider = "cpu"
            }
            val config = OfflineRecognizerConfig().apply {
                modelConfig = modelCfg
                decodingMethod = "greedy_search"
            }
            val rec = OfflineRecognizer(appCtx.assets, config)
            Log.i(TAG, "[sherpa] model loaded in ${SystemClock.elapsedRealtime() - t0}ms")
            recognizer = rec
            return rec
        }
    }

    @Throws(Exception::class)
    override fun transcribe(pcm16k: ShortArray): AsrResult {
        val rec = ensureInit()
        val t0 = SystemClock.elapsedRealtime()
        // int16 -> float [-1, 1]
        val samples = FloatArray(pcm16k.size) { i -> pcm16k[i] / 32768.0f }
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val text = result.text.trim()
            val latencyMs = SystemClock.elapsedRealtime() - t0
            logTranscribeLatency(name, latencyMs, text.length, "on-device sense-voice-small-int8")
            return AsrResult(text, latencyMs)
        } finally {
            stream.release()
        }
    }

    override fun release() {
        synchronized(initLock) {
            try {
                recognizer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "[sherpa] release failed", e)
            }
            recognizer = null
        }
    }
}
