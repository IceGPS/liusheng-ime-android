package com.icegps.voiceime.service.asr

import android.util.Log

/**
 * 转写后端可插拔接口（V2）。
 *
 * V2 有两种实现：
 *  - [MnnAsrBackend]：端侧 MNN 跑 Qwen3-ASR-0.6B INT8，不依赖网络（默认优先）
 *  - [LanAsrBackend]：局域网 WebSocket 发往 PC 服务端（V1 老链路，备用/降级）
 *
 * 约定：
 *  - transcribe() 必须在后台线程调用（内部阻塞等待）
 *  - 每次转写耗时打 logcat（tag=VoiceIME，INFO 级），方便用户回传延迟数据
 */
interface AsrBackend {
    /** 后端标识（打 log 用）："mnn" / "lan" */
    val name: String

    /**
     * 是否可用。
     *  - MNN：native 库加载成功 且 模型文件就绪
     *  - LAN：已配置服务端 IP
     * FloatBallService 用它决定是否降级。
     */
    fun isAvailable(): Boolean

    /**
     * 同步转写 16kHz 单声道 int16 PCM。
     * @return 文本 + 本次转写耗时（毫秒）
     * @throws Exception 转写失败（调用方统一 toast 提示）
     */
    @Throws(Exception::class)
    fun transcribe(pcm16k: ShortArray): AsrResult

    /** 释放底层资源（模型句柄等），默认空实现 */
    fun release() {}
}

/** 单次转写结果 */
data class AsrResult(
    /** 识别文本（已 trim，可能为空字符串） */
    val text: String,
    /** 本次转写端到端耗时（毫秒） */
    val latencyMs: Long,
)

/**
 * 统一的转写耗时日志：tag=VoiceIME，INFO 级。
 * 只记录耗时和文本长度，不记录文本内容（隐私）。
 */
internal fun logTranscribeLatency(backend: String, latencyMs: Long, textLen: Int, extra: String = "") {
    val suffix = if (extra.isEmpty()) "" else " $extra"
    Log.i("VoiceIME", "[$backend] transcribe latency=${latencyMs}ms textLen=$textLen$suffix")
}
