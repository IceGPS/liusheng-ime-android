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
    const val DEFAULT_MODEL_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main"

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
    fun getModelUrl(ctx: Context): String {
        val stored = prefs(ctx).getString(KEY_MODEL_URL, DEFAULT_MODEL_URL) ?: DEFAULT_MODEL_URL
        // 迁移：老版本默认地址是 GitHub Release（两种历史形式）：
        //   1. https://github.com/IceGPS/voice-ime-android/releases（V1-V10）
        //   2. https://github.com/.../releases/download/model-qwen3-asr-0.6b-int8（Qwen3 时代）
        // 2026-10-12 起默认地址改为 HuggingFace 的 SenseVoice。
        // 老用户升级 App 后 SharedPreferences 里还是旧地址，
        // 下载 model.int8.onnx 会报 HTTP 404（旧地址没有这个文件）。
        // 检测到旧地址特征时自动替换为新默认值并写回，只迁移一次。
        return if (stored.contains("github.com/IceGPS/voice-ime-android/releases")) {
            prefs(ctx).edit().putString(KEY_MODEL_URL, DEFAULT_MODEL_URL).apply()
            DEFAULT_MODEL_URL
        } else {
            stored
        }
    }

    fun setModelUrl(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_MODEL_URL, v.trim().trimEnd('/')).apply()
    }

    /** 模型是否已下载并校验通过（下载成功后置 true；重新下载开始时清掉） */
    fun isModelReady(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_MODEL_READY, false)

    fun setModelReady(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_MODEL_READY, v).apply()
    }

    // ---------- VAD（语音活动检测） ----------

    private const val KEY_VAD_ENABLED = "vad_enabled"

    /** VAD 是否启用：点按开始、静音自动停止。默认关闭（按住说话模式）。 */
    fun isVadEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VAD_ENABLED, false)

    fun setVadEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_VAD_ENABLED, v).apply()
    }

    private const val KEY_VAD_SILENCE_MS = "vad_silence_ms"

    /** VAD 静音超时毫秒数，默认 1500ms */
    fun getVadSilenceMs(ctx: Context): Int =
        prefs(ctx).getInt(KEY_VAD_SILENCE_MS, 1500)

    fun setVadSilenceMs(ctx: Context, v: Int) {
        prefs(ctx).edit().putInt(KEY_VAD_SILENCE_MS, v.coerceIn(500, 5000)).apply()
    }

    /** 数字/标点区是否展开，默认关闭 */
    fun isNumPadExpanded(ctx: Context): Boolean =
        prefs(ctx).getBoolean("numpad_expanded", false)

    fun setNumPadExpanded(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean("numpad_expanded", v).apply()
    }

    // ---------- 拼音键盘（V32） ----------

    private const val KEY_KB_MODE = "kb_mode"

    /** 键盘模式：语音键行 */
    const val KB_VOICE = "voice"

    /** 键盘模式：拼音键盘 */
    const val KB_PINYIN = "pinyin"

    /** 当前键盘模式（语音键行 / 拼音键盘），默认语音键行 */
    fun getKbMode(ctx: Context): String =
        prefs(ctx).getString(KEY_KB_MODE, KB_VOICE) ?: KB_VOICE

    fun setKbMode(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_KB_MODE, v).apply()
    }

    private const val KEY_PINYIN_SCHEME = "pinyin_scheme"

    /**
     * 拼音方案：quanpin（全拼）/ xiaohe（小鹤双拼），默认全拼。
     * 第一阶段桩实现仅支持全拼；第二阶段 RIME 接入后生效。
     */
    fun getPinyinScheme(ctx: Context): String =
        prefs(ctx).getString(KEY_PINYIN_SCHEME, "quanpin") ?: "quanpin"

    fun setPinyinScheme(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_PINYIN_SCHEME, v).apply()
    }

    // ---------- 剪贴板历史（V69） ----------

    private const val KEY_CLIP_ENABLED = "clip_enabled"

    /**
     * 剪贴板历史总开关：本机记录 + 局域网共享。
     * 默认关闭（隐私优先，需用户明确开启）；首次配对成功后自动开启。
     */
    fun isClipEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CLIP_ENABLED, false)

    fun setClipEnabled(ctx: Context, v: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CLIP_ENABLED, v).apply()
    }

    private const val KEY_CLIP_DEVICE_ID = "clip_device_id"

    /** 本机设备 UUID（局域网同步的设备标识，防回环用），首次生成后不变 */
    fun getClipDeviceId(ctx: Context): String {
        val p = prefs(ctx)
        var id = p.getString(KEY_CLIP_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            id = java.util.UUID.randomUUID().toString()
            p.edit().putString(KEY_CLIP_DEVICE_ID, id).apply()
        }
        return id
    }

    private const val KEY_CLIP_PAIRED = "clip_paired_json"

    /** 已配对设备列表（JSON 数组：[{id,name,key,host,port}]） */
    fun getClipPairedJson(ctx: Context): String =
        prefs(ctx).getString(KEY_CLIP_PAIRED, "[]") ?: "[]"

    fun setClipPairedJson(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_CLIP_PAIRED, v).apply()
    }
}
