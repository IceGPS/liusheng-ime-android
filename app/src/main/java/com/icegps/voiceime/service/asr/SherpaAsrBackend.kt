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

        /** 模型是否就绪：model.int8.onnx + tokens.txt 存在且非空 */
        fun isModelReady(ctx: Context): Boolean {
            val dir = modelDir(ctx)
            return listOf(MODEL_FILE, TOKENS_FILE).all { name ->
                val f = File(dir, name)
                f.isFile && f.length() > 0L
            }
        }
    }

    override fun isAvailable(): Boolean = isModelReady(appCtx)

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
                numThreads = 4
            }
            val config = OfflineRecognizerConfig().apply {
                modelConfig = modelCfg
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
