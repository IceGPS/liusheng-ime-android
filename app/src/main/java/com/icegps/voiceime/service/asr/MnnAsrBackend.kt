package com.icegps.voiceime.service.asr

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 端侧后端：MNN Omni 引擎跑 Qwen3-ASR-0.6B INT8。
 *
 * 要点（来自预研报告第五节 + MNN 官方 MnnLlmChat demo）：
 *  - 只用 CPU（移动端 GPU decode 反而慢 15~60 倍，Vulkan 会 hang）
 *  - greedy 采样（temp=0, top_k=1；ASR 稀有 token 置信度低，采样会翻车）
 *  - 音频走 `<audio>wav路径</audio>` prompt，Omni 引擎内部解析并做
 *    RMS 归一化 → kaldi-native-fbank → audio encoder
 *
 * 模型文件放在 App 私有目录（不打包进 APK，首次启动下载）：
 *   <filesDir>/models/qwen3-asr-0.6b-int8/
 *     llm.mnn / audio.mnn / llm_config.json（下载得到）
 *     config.json（App 生成的运行时配置：greedy + 纯 CPU）
 *
 * native 库 `libvoiceime_mnn.so` 由 app/src/main/cpp/mnn_asr_bridge.cpp
 * 编出（需 NDK + MNN 源码，见 README）。库缺失时本后端 isAvailable()=false，
 * 调用方自动降级到局域网——Linux 上直接 gradle/kotlinc 构建的 APK 就是这种情况。
 */
class MnnAsrBackend(private val appCtx: Context) : AsrBackend {

    override val name: String = "mnn"

    @Volatile private var handle: Long = 0L
    private val initLock = Any()

    override fun isAvailable(): Boolean = nativeLoaded && isModelReady(appCtx)

    /**
     * 同步转写：PCM 写临时 WAV → JNI 阻塞转写 → 返回文本。
     * 模型首次加载较慢（数秒），只在第一次转写时发生，且已在后台线程。
     */
    @Throws(Exception::class)
    override fun transcribe(pcm16k: ShortArray): AsrResult {
        val h = ensureInit()
        val wav = writeWav(pcm16k)
        try {
            val t0 = SystemClock.elapsedRealtime()
            val text = nativeTranscribe(h, wav.absolutePath, MAX_NEW_TOKENS).trim()
            val latencyMs = SystemClock.elapsedRealtime() - t0
            logTranscribeLatency(name, latencyMs, text.length, "on-device qwen3-asr-0.6b-int8")
            return AsrResult(text, latencyMs)
        } finally {
            wav.delete()
        }
    }

    override fun release() {
        synchronized(initLock) {
            if (handle != 0L) {
                try {
                    nativeRelease(handle)
                } catch (e: Exception) {
                    Log.w(TAG, "nativeRelease failed", e)
                }
                handle = 0L
            }
        }
    }

    /** 懒加载模型（线程安全）：只在第一次转写时 load，之后复用句柄 */
    @Throws(Exception::class)
    private fun ensureInit(): Long {
        synchronized(initLock) {
            if (handle != 0L) return handle
            if (!nativeLoaded) throw IllegalStateException("MNN native 库未加载")
            if (!isModelReady(appCtx)) throw IllegalStateException("端侧模型文件未就绪")
            val dir = modelDir(appCtx)
            ensureConfigJson(appCtx)
            Log.i(TAG, "[mnn] loading model from ${dir.absolutePath} ...")
            val t0 = SystemClock.elapsedRealtime()
            val h = nativeInit(dir.absolutePath, runtimeConfigJson())
            if (h == 0L) throw IllegalStateException("MNN 模型加载失败（看 logcat 的 MNN/Omni 日志）")
            Log.i(TAG, "[mnn] model loaded in ${SystemClock.elapsedRealtime() - t0}ms")
            handle = h
            return h
        }
    }

    /** int16 PCM → 16kHz 单声道 WAV（Omni 引擎用 <audio> 路径读文件） */
    @Throws(Exception::class)
    private fun writeWav(pcm: ShortArray): File {
        val wav = File.createTempFile("asr_", ".wav", appCtx.cacheDir)
        FileOutputStream(wav).use { out ->
            val dataBytes = pcm.size * 2
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(36 + dataBytes)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16) // fmt chunk size
            header.putShort(1) // PCM
            header.putShort(1) // mono
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * 2) // byte rate
            header.putShort(2) // block align
            header.putShort(16) // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataBytes)
            out.write(header.array())
            val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) body.putShort(s)
            out.write(body.array())
        }
        return wav
    }

    // ---------- JNI（实现见 app/src/main/cpp/mnn_asr_bridge.cpp） ----------

    private external fun nativeInit(modelDir: String, runtimeConfigJson: String): Long
    private external fun nativeTranscribe(handle: Long, wavPath: String, maxNewTokens: Int): String
    private external fun nativeRelease(handle: Long)

    companion object {
        private const val TAG = "VoiceIME"
        private const val LIB_NAME = "voiceime_mnn"
        private const val SAMPLE_RATE = 16000
        private const val MAX_NEW_TOKENS = 256

        /** 模型目录名（版本变化时改这里即可） */
        const val MODEL_DIR_NAME = "qwen3-asr-0.6b-int8"

        /** native 库是否加载成功（加载失败则本后端不可用，优雅降级） */
        @Volatile
        var nativeLoaded: Boolean = false
            private set

        init {
            try {
                System.loadLibrary(LIB_NAME)
                nativeLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                // 无 NDK 构建的 APK（或 JNI 未编入）走这里：isAvailable()=false → 降级局域网
                Log.w(TAG, "[mnn] native lib $LIB_NAME not found, on-device ASR disabled: ${e.message}")
                nativeLoaded = false
            }
        }

        /** 模型根目录：<filesDir>/models/qwen3-asr-0.6b-int8/ */
        fun modelDir(ctx: Context): File = File(File(ctx.filesDir, "models"), MODEL_DIR_NAME)

        /**
         * 模型是否就绪：llm.mnn + audio.mnn + llm_config.json 存在且非空。
         * config.json 由 App 自己生成（不计入）。
         */
        fun isModelReady(ctx: Context): Boolean {
            val dir = modelDir(ctx)
            return listOf("llm.mnn", "audio.mnn", "llm_config.json").all { name ->
                val f = File(dir, name)
                f.isFile && f.length() > 0L
            }
        }

        /** 已下载模型文件总大小（字节），设置页显示用 */
        fun modelSizeBytes(ctx: Context): Long {
            val dir = modelDir(ctx)
            if (!dir.isDirectory) return 0L
            return dir.listFiles()?.sumOf { it.length() } ?: 0L
        }

        /**
         * 生成运行时 config.json（不存在时写入；已存在则不动，方便以后调参）。
         * 关键项：is_audio + audio_type=qwen3_asr + greedy 采样 + 纯 CPU。
         */
        fun ensureConfigJson(ctx: Context) {
            val f = File(modelDir(ctx), "config.json")
            if (f.isFile && f.length() > 0L) return
            f.parentFile?.mkdirs()
            f.writeText(
                """{
  "is_audio": true,
  "audio_type": "qwen3_asr",
  "audio_model": "audio.mnn",
  "sampler_type": "greedy",
  "temperature": 0.0,
  "top_k": 1,
  "max_new_tokens": $MAX_NEW_TOKENS,
  "backend_type": "cpu",
  "thread_num": 4
}
""",
            )
            Log.i(TAG, "[mnn] wrote runtime config.json")
        }

        /** 传给 nativeInit 的运行时覆盖配置（JSON 字符串） */
        internal fun runtimeConfigJson(): String =
            """{"backend_type":"cpu","thread_num":4}"""
    }
}
