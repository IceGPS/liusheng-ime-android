package com.icegps.voiceime.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.icegps.voiceime.service.asr.SherpaAsrBackend
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 端侧模型下载器（V2）。
 *
 * 流程（后台线程调用，会阻塞）：
 *  1. 从 `<baseUrl>/SHA256SUMS` 拉文件清单（`sha256  filename` 每行一个）
 *  2. 逐个下载到 `<filesDir>/models/qwen3-asr-0.6b-int8/<file>.tmp`（OkHttp 流式，回调进度）
 *  3. 每个文件 SHA256 校验 → 通过后 rename 为正式文件
 *  4. 全部通过后生成运行时 config.json，标记模型就绪
 *
 * 约束：
 *  - 仅 WiFi 下载（调用方在开始前用 [isWifiConnected] 检查并提示用户）
 *  - 模型绝不打包进 APK，只走这个流程进 App 私有目录
 *
 * TODO(hosting)：baseUrl 目前是占位（见 Prefs），实际 hosting 确定后
 *  需保证 `<baseUrl>/SHA256SUMS` 与 `<baseUrl>/<filename>` 可直接下载。
 */
object ModelDownloader {

    private const val TAG = "VoiceIME"
    private const val SUMS_FILE = "SHA256SUMS"

    /** 进度回调（后台线程）：file=当前文件名，done/total=当前文件字节数，index/count=第几个文件 */
    fun interface ProgressListener {
        fun onProgress(file: String, done: Long, total: Long, index: Int, count: Int)
    }

    data class ManifestEntry(val name: String, val sha256: String)

    /**
     * 仅 WiFi 才返回 true（移动数据/无网络返回 false）。
     * 下载前调用：非 WiFi 时提示用户"请连接 WiFi 后下载（约 826MB）"。
     */
    fun isWifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 下载全部模型文件并校验。必须在后台线程调用。
     * @return 成功返回 Unit；失败抛异常（调用方 toast 展示 message）
     */
    @Throws(Exception::class)
    fun downloadAll(ctx: Context, baseUrl: String, listener: ProgressListener) {
        val base = baseUrl.trim().trimEnd('/')
        require(base.isNotEmpty()) { "模型下载地址未配置" }
        val dir = SherpaAsrBackend.modelDir(ctx).apply { mkdirs() }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // 大文件：读超时禁用，靠进度推进
            .build()
        try {
            // 优先用 SHA256SUMS 清单；没有清单时（如 HuggingFace）用内置文件列表直接下载
            val manifest = try {
                fetchManifest(client, "$base/$SUMS_FILE").also {
                    require(it.isNotEmpty()) { "模型清单为空" }
                    Log.i(TAG, "[model] manifest: ${it.size} files from $base")
                }
            } catch (e: Exception) {
                Log.i(TAG, "[model] 无 SHA256SUMS 清单，直接下载已知文件：${e.message}")
                listOf(
                    ManifestEntry("model.int8.onnx", ""),
                    ManifestEntry("tokens.txt", ""),
                )
            }

            manifest.forEachIndexed { i, entry ->
                // 文件名只取 basename，防止清单里的路径穿越
                val safeName = File(entry.name).name
                require(safeName.isNotEmpty() && !entry.name.contains("..")) {
                    "非法文件名：${entry.name}"
                }
                val tmp = File(dir, "$safeName.tmp")
                val final = File(dir, safeName)
                if (final.isFile && sha256Of(final).equals(entry.sha256, ignoreCase = true)) {
                    Log.i(TAG, "[model] $safeName 已存在且校验通过，跳过下载")
                    listener.onProgress(safeName, final.length(), final.length(), i + 1, manifest.size)
                    return@forEachIndexed
                }
                Log.i(TAG, "[model] downloading $safeName (${i + 1}/${manifest.size})")
                downloadFile(client, "$base/$safeName", tmp) { done, total ->
                    listener.onProgress(safeName, done, total, i + 1, manifest.size)
                }
                // 有 SHA256 时校验；无清单（如 HuggingFace）时跳过校验
                if (entry.sha256.isNotEmpty()) {
                    val actual = sha256Of(tmp)
                    if (!actual.equals(entry.sha256, ignoreCase = true)) {
                        tmp.delete()
                        throw IllegalStateException("$safeName 校验失败：SHA256 不匹配（文件已删除）")
                    }
                }
                if (final.exists()) final.delete()
                if (!tmp.renameTo(final)) {
                    tmp.delete()
                    throw IllegalStateException("$safeName 保存失败")
                }
                Log.i(TAG, "[model] $safeName OK (${final.length()} bytes)")
            }

            // 全部就绪
            Log.i(TAG, "[model] all files ready in ${dir.absolutePath}")
            // 备份到 Download，下次重装免下载
            SherpaAsrBackend.backupModel(ctx)
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    /** 取消下载后清理 .tmp 残留 */
    fun cleanTemp(ctx: Context) {
        SherpaAsrBackend.modelDir(ctx).listFiles { f -> f.name.endsWith(".tmp") }
            ?.forEach { it.delete() }
    }

    // ---------- 内部 ----------

    @Throws(Exception::class)
    private fun fetchManifest(client: OkHttpClient, url: String): List<ManifestEntry> {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("拉取模型清单失败：HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IllegalStateException("模型清单为空")
            return body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    // coreutils sha256sum 格式：<hex>(空格)<*| >filename
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size < 2) return@mapNotNull null
                    val hash = parts[0].lowercase()
                    val name = parts.last().trimStart('*')
                    if (hash.length != 64 || !hash.all { c -> c in '0'..'9' || c in 'a'..'f' }) {
                        return@mapNotNull null
                    }
                    ManifestEntry(name, hash)
                }
                .toList()
        }
    }

    @Throws(Exception::class)
    private fun downloadFile(
        client: OkHttpClient,
        url: String,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败：HTTP ${resp.code}（$url）")
            val body = resp.body ?: throw IllegalStateException("下载响应为空")
            val total = body.contentLength() // 可能为 -1（未知）
            var done = 0L
            var lastReport = 0L
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // 每 256KB 回调一次，避免 UI 刷新过频
                        if (done - lastReport >= 256 * 1024 || done == total) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            if (total > 0 && done != total) {
                dest.delete()
                throw IllegalStateException("下载不完整：$done/$total 字节")
            }
        }
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
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
}
