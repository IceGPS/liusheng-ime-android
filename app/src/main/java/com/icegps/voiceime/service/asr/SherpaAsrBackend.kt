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

        /**
         * 已知-good 文件指纹（sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17 release，
         * 与仓库 models/sense-voice-small/ 下的拷贝一致）。
         * V22 新增：下载链路在无 SHA256SUMS 清单时（HuggingFace）不做任何校验，
         * 残缺文件能通过 isModelReady 的大小门限（>200MB），随后在 new OfflineRecognizer
         * 里以未捕获的 Ort::Exception 触发 SIGABRT（上游 issue #3019 同款闪退）。
         * 在构造 recognizer 之前先验完整性：坏文件直接删掉并报可读错误，不再闪退。
         */
        private const val MODEL_SIZE = 239233841L
        private const val MODEL_SHA256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"
        private const val TOKENS_SIZE = 315894L
        private const val TOKENS_SHA256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"

        /** 进程内缓存：完整性已验证通过后不再重复算 SHA256（228MB 在手机上约 2~3 秒） */
        @Volatile private var integrityOk = false

        /**
         * 校验模型完整性。大小必查；SHA256 每个进程只算一次。
         * @throws IllegalStateException 文件缺失/损坏（调用方 toast 展示，不闪退）
         */
        @Throws(IllegalStateException::class)
        fun verifyModelIntegrity(ctx: Context) {
            if (integrityOk) return
            val dir = modelDir(ctx)
            val modelFile = File(dir, MODEL_FILE)
            val tokensFile = File(dir, TOKENS_FILE)
            Log.i(TAG, "[sherpa] integrity check: model=${modelFile.length()}/$MODEL_SIZE bytes, " +
                    "tokens=${tokensFile.length()}/$TOKENS_SIZE bytes")
            if (modelFile.length() != MODEL_SIZE || tokensFile.length() != TOKENS_SIZE) {
                dropBadFiles(ctx, modelFile, tokensFile)
                throw IllegalStateException("模型文件不完整已删除，请在设置中重新下载")
            }
            if (sha256Of(modelFile) != MODEL_SHA256 || sha256Of(tokensFile) != TOKENS_SHA256) {
                dropBadFiles(ctx, modelFile, tokensFile)
                throw IllegalStateException("模型文件校验失败已删除，请在设置中重新下载")
            }
            integrityOk = true
            Log.i(TAG, "[sherpa] integrity check passed")
        }

        private fun dropBadFiles(ctx: Context, vararg files: File) {
            integrityOk = false
            files.forEach { f ->
                try { if (f.exists() && !f.delete()) Log.w(TAG, "[sherpa] 删除坏文件失败：$f") }
                catch (e: Exception) { Log.w(TAG, "[sherpa] 删除坏文件异常", e) }
            }
            // 损坏的备份也没有保留价值，避免 restoreFromBackup 又把它拷回来
            try {
                val bak = File(backupDir(), MODEL_FILE)
                if (bak.exists() && bak.length() != MODEL_SIZE && !bak.delete())
                    Log.w(TAG, "[sherpa] 删除坏备份失败")
                val bakT = File(backupDir(), TOKENS_FILE)
                if (bakT.exists() && bakT.length() != TOKENS_SIZE && !bakT.delete())
                    Log.w(TAG, "[sherpa] 删除坏备份失败")
            } catch (e: Exception) { Log.w(TAG, "[sherpa] 清理备份异常", e) }
            try { com.icegps.voiceime.util.Prefs.setModelReady(ctx, false) } catch (_: Exception) {}
        }

        private fun sha256Of(file: File): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** 模型根目录：<filesDir>/models/sense-voice-small/ */
        fun modelDir(ctx: Context): File = File(File(ctx.filesDir, "models"), MODEL_DIR_NAME)

        /** 旧备份目录（V61 之前）：/sdcard/Download/voice-ime-models/sense-voice-small/（卸载不删除）；
         *  仅用于老用户升级时的一次性迁移读取，新备份走 MediaStore */
        fun backupDir(): File = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ),
            "voice-ime-models/$MODEL_DIR_NAME"
        )

        /** 模型文件总大小（字节），用于 UI 显示 */
        fun modelSizeBytes(ctx: Context): Long {
            val dir = modelDir(ctx)
            return listOf(MODEL_FILE, TOKENS_FILE).sumOf { name ->
                File(dir, name).takeIf { it.isFile }?.length() ?: 0L
            }
        }

        /** 模型是否就绪：model.int8.onnx + tokens.txt 存在且大小合理（防止加载未下载完的文件） */
        fun isModelReady(ctx: Context): Boolean {
            val dir = modelDir(ctx)
            return isFilesReady(File(dir, MODEL_FILE), File(dir, TOKENS_FILE))
        }

        private fun isFilesReady(modelFile: File, tokensFile: File): Boolean {
            // model.int8.onnx 约 228MB，tokens.txt 约 308KB；小于阈值视为未下载完
            return modelFile.isFile && modelFile.length() > 200L * 1024 * 1024 &&
                   tokensFile.isFile && tokensFile.length() > 100L * 1024
        }

        /**
         * 从备份恢复模型（卸载重装后免重新下载）。
         * V61 起备份改走 MediaStore（无需任何存储权限），MANAGE_EXTERNAL_STORAGE 已移除。
         * @return 成功恢复返回 true
         */
        fun restoreFromBackup(ctx: Context): Boolean {
            if (isModelReady(ctx)) return true
            // 新位置优先：MediaStore（无需权限）
            if (restoreFromMediaStore(ctx)) return true
            // 旧位置：V61 之前的 File 备份（老用户升级场景；Android 11+ 无 MANAGE 权限时不可读则跳过）
            if (restoreFromLegacyFile(ctx)) {
                // 迁移到新位置，下次走 MediaStore
                backupModel(ctx)
                return true
            }
            return false
        }

        /**
         * 备份模型（V61）。
         * API 29+：MediaStore Download（零权限）；API 26-28：旧 File 路径。
         * 备份用于卸载重装后免重新下载。
         */
        fun backupModel(ctx: Context) {
            if (!isModelReady(ctx)) return
            if (android.os.Build.VERSION.SDK_INT >= 29) backupToMediaStore(ctx)
            else backupToLegacyFile(ctx)
        }

        /** MediaStore 备份相对路径（Download 下） */
        private const val BACKUP_RELATIVE_PATH = "Download/voice-ime-models/sense-voice-small"

        private fun backupToMediaStore(ctx: Context) {
            try {
                val resolver = ctx.contentResolver
                val files = listOf(MODEL_FILE to "application/octet-stream", TOKENS_FILE to "text/plain")
                for ((name, mime) in files) {
                    val src = File(modelDir(ctx), name)
                    if (!src.isFile) continue
                    // 先删旧备份，避免重复条目
                    deleteMediaStoreEntry(ctx, name)
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Downloads.MIME_TYPE, mime)
                        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, BACKUP_RELATIVE_PATH)
                        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                    ) ?: throw IllegalStateException("MediaStore insert 失败：$name")
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            src.inputStream().use { it.copyTo(out) }
                        } ?: throw IllegalStateException("无法打开输出流：$name")
                        resolver.update(
                            uri,
                            android.content.ContentValues().apply {
                                put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
                            },
                            null, null,
                        )
                    } catch (e: Exception) {
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                        throw e
                    }
                }
                Log.i(TAG, "[sherpa] 模型已备份到 MediaStore Download")
            } catch (e: Exception) {
                Log.w(TAG, "[sherpa] MediaStore 备份失败", e)
            }
        }

        private fun deleteMediaStoreEntry(ctx: Context, displayName: String) {
            try {
                val resolver = ctx.contentResolver
                resolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Downloads._ID),
                    "${android.provider.MediaStore.Downloads.DISPLAY_NAME}=? AND " +
                        "${android.provider.MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(displayName, "$BACKUP_RELATIVE_PATH/"),
                    null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
                    while (c.moveToNext()) {
                        val uri = android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            c.getLong(idCol),
                        )
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[sherpa] 删除旧 MediaStore 备份失败", e)
            }
        }

        private fun findMediaStoreEntry(ctx: Context, displayName: String): android.net.Uri? {
            return try {
                ctx.contentResolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(android.provider.MediaStore.Downloads._ID),
                    "${android.provider.MediaStore.Downloads.DISPLAY_NAME}=? AND " +
                        "${android.provider.MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(displayName, "$BACKUP_RELATIVE_PATH/"),
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
                        android.content.ContentUris.withAppendedId(
                            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            c.getLong(idCol),
                        )
                    } else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "[sherpa] 查询 MediaStore 备份失败", e)
                null
            }
        }

        private fun restoreFromMediaStore(ctx: Context): Boolean {
            if (android.os.Build.VERSION.SDK_INT < 29) return false
            return try {
                val resolver = ctx.contentResolver
                val dir = modelDir(ctx).apply { mkdirs() }
                val files = listOf(MODEL_FILE to 200L * 1024 * 1024, TOKENS_FILE to 100L * 1024)
                for ((name, minSize) in files) {
                    val uri = findMediaStoreEntry(ctx, name) ?: return false
                    val size = resolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null,
                    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1 } ?: -1
                    if (size < minSize) return false
                    resolver.openInputStream(uri)?.use { input ->
                        File(dir, name).outputStream().use { input.copyTo(it) }
                    } ?: return false
                }
                com.icegps.voiceime.util.Prefs.setModelReady(ctx, true)
                Log.i(TAG, "[sherpa] 模型已从 MediaStore 备份恢复，无需重新下载")
                true
            } catch (e: Exception) {
                Log.w(TAG, "[sherpa] 从 MediaStore 恢复失败", e)
                false
            }
        }

        /** 旧 File 备份恢复（V61 之前版本，尽力而为） */
        private fun restoreFromLegacyFile(ctx: Context): Boolean {
            return try {
                val backup = backupDir()
                val modelBak = File(backup, MODEL_FILE)
                val tokensBak = File(backup, TOKENS_FILE)
                if (!modelBak.isFile || modelBak.length() < 200L * 1024 * 1024) return false
                if (!tokensBak.isFile || tokensBak.length() < 100L * 1024) return false
                val dir = modelDir(ctx).apply { mkdirs() }
                modelBak.copyTo(File(dir, MODEL_FILE), overwrite = true)
                tokensBak.copyTo(File(dir, TOKENS_FILE), overwrite = true)
                com.icegps.voiceime.util.Prefs.setModelReady(ctx, true)
                Log.i(TAG, "[sherpa] 模型已从旧 File 备份恢复")
                true
            } catch (e: Exception) {
                Log.w(TAG, "[sherpa] 从旧 File 备份恢复失败", e)
                false
            }
        }

        /** 旧 File 备份（仅 API 26-28；29+ 走 MediaStore） */
        private fun backupToLegacyFile(ctx: Context) {
            if (!isModelReady(ctx)) return
            try {
                val dir = modelDir(ctx)
                val backup = backupDir().apply { mkdirs() }
                File(dir, MODEL_FILE).copyTo(File(backup, MODEL_FILE), overwrite = true)
                File(dir, TOKENS_FILE).copyTo(File(backup, TOKENS_FILE), overwrite = true)
                Log.i(TAG, "[sherpa] 模型已备份到 ${backup.absolutePath}")
            } catch (e: Exception) {
                Log.w(TAG, "[sherpa] File 备份失败", e)
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

    /**
     * 预热：在后台线程提前把 ~230MB 模型载入内存，并用 recognizer
     * 强引用占住（避免 GC 释放），首次语音输入不再现载卡顿。
     * 模型未下载时静默跳过（不 toast、不抛异常）。
     */
    fun warmUp() {
        try {
            if (!isAvailable()) return
            ensureInit()
            Log.i(TAG, "[sherpa] warm-up done")
        } catch (e: Exception) {
            Log.w(TAG, "[sherpa] warm-up failed: ${e.message}")
        }
    }

    @Throws(Exception::class)
    private fun ensureInit(): OfflineRecognizer {
        synchronized(initLock) {
            recognizer?.let { return it }
            if (!isModelReady(appCtx)) throw IllegalStateException("端侧模型文件未就绪")
            // V22：先验完整性。坏文件在这里拦截（删掉+抛可读错误），
            // 避免 new OfflineRecognizer 里未捕获的 Ort::Exception 直接 SIGABRT 闪退。
            verifyModelIntegrity(appCtx)
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
            // 注意：用绝对路径加载模型时，AssetManager 必须传 null，否则 sherpa-onnx 会 FATAL
            // （见 https://github.com/k2-fsa/sherpa-onnx/issues/2562）
            val rec = OfflineRecognizer(null, config)
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
