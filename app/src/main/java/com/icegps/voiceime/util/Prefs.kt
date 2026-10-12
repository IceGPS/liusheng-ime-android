package com.icegps.voiceime.util

import android.content.Context

/**
 * 应用配置（SharedPreferences 封装）。
 * 存：服务端 IP/端口、悬浮球大小/透明度、转写引擎、模型下载地址/状态。
 */
object Prefs {
    private const val NAME = "voice_ime_prefs"
    private const val KEY_HOST = "server_host"
    private const val KEY_PORT = "server_port"
    private const val KEY_BALL_SIZE = "ball_size_dp"
    private const val KEY_BALL_ALPHA = "ball_alpha_pct"
    private const val KEY_ONBOARDED = "onboarded"
    // V2 新增
    private const val KEY_ENGINE = "asr_engine"
    private const val KEY_MODEL_URL = "model_base_url"
    private const val KEY_MODEL_READY = "model_ready"

    /** 转写引擎：端侧优先（默认）/ 仅局域网 */
    const val ENGINE_ONDEVICE_FIRST = "ondevice_first"
    const val ENGINE_LAN_ONLY = "lan_only"

    /**
     * 模型下载基地址。
     * GitHub Release: model-qwen3-asr-0.6b-int8
     * 需保证 `<baseUrl>/SHA256SUMS` 与 `<baseUrl>/<filename>` 可直接下载。
     */
    const val DEFAULT_MODEL_URL = "https://github.com/IceGPS/voice-ime-android/releases/download/model-qwen3-asr-0.6b-int8"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 服务端 IP（PC 的局域网地址），默认空，首次使用引导填写 */
    fun getHost(ctx: Context): String =
        prefs(ctx).getString(KEY_HOST, "") ?: ""

    fun setHost(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_HOST, v.trim()).apply()
    }

    /** 服务端端口，默认 6016（与 CapsWriter 服务端一致） */
    fun getPort(ctx: Context): Int = prefs(ctx).getInt(KEY_PORT, 6016)

    fun setPort(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_PORT, v).apply()
    }

    /** 悬浮球直径 dp，默认 64 */
    fun getBallSizeDp(ctx: Context): Int = prefs(ctx).getInt(KEY_BALL_SIZE, 64)

    fun setBallSizeDp(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_BALL_SIZE, v).apply()
    }

    /** 悬浮球不透明度 20~100，默认 90 */
    fun getBallAlphaPct(ctx: Context): Int = prefs(ctx).getInt(KEY_BALL_ALPHA, 90)

    fun setBallAlphaPct(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_BALL_ALPHA, v.coerceIn(20, 100)).apply()
    }

    /** 是否已完成权限引导 */
    fun isOnboarded(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    // ---------- V2：转写引擎与端侧模型 ----------

    /** 转写引擎选择，默认端侧优先 */
    fun getEngine(ctx: Context): String =
        prefs(ctx).getString(KEY_ENGINE, ENGINE_ONDEVICE_FIRST) ?: ENGINE_ONDEVICE_FIRST

    fun setEngine(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_ENGINE, v).apply()
    }

    /** 模型下载基地址（设置页可改，默认占位） */
    fun getModelUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_MODEL_URL, DEFAULT_MODEL_URL) ?: DEFAULT_MODEL_URL

    fun setModelUrl(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_MODEL_URL, v.trim().trimEnd('/')).apply()
    }

    /** 模型是否已下载并校验通过（下载成功后置 true；重新下载开始时清掉） */
    fun isModelReady(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_MODEL_READY, false)

    fun setModelReady(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MODEL_READY, v).apply()
    }
}
