package com.icegps.voiceime.util

import org.json.JSONObject
import java.util.UUID

/**
 * 与 PC 服务端（CapsWriter 协议）通信的消息定义。
 *
 * 照抄 Windows 客户端 core/protocol.py 的字段，保证两端一致：
 *  - 客户端 -> 服务端：AudioMessage（JSON 文本帧）
 *  - 服务端 -> 客户端：RecognitionMessage（JSON 文本帧）
 *
 * 音频格式：float32 小端字节序，16kHz 单声道，Base64 编码
 * （Windows 端是 np.float32 数组 tobytes()，即小端）。
 */
object Protocol {

    /** 客户端 -> 服务端：音频数据消息 */
    data class AudioMessage(
        val taskId: String = UUID.randomUUID().toString(),
        val source: String = "mic",
        /** Base64 编码的 float32 PCM；结束包为空字符串 */
        val data: String,
        val isFinal: Boolean,
        /** 录音开始时间戳（epoch 秒，浮点） */
        val timeStart: Double,
        val segDuration: Double = 15.0,
        val segOverlap: Double = 2.0,
        val context: String = "",
        val language: String = "auto",
    ) {
        fun toJson(): String = JSONObject().apply {
            put("task_id", taskId)
            put("source", source)
            put("data", data)
            put("is_final", isFinal)
            put("time_start", timeStart)
            put("seg_duration", segDuration)
            put("seg_overlap", segOverlap)
            put("context", context)
            put("language", language)
        }.toString()
    }

    /** 服务端 -> 客户端：识别结果消息（只取我们需要的字段） */
    data class RecognitionMessage(
        val taskId: String,
        val isFinal: Boolean,
        /** 最终转写文本 */
        val text: String,
    ) {
        companion object {
            fun fromJson(json: String): RecognitionMessage? = try {
                val o = JSONObject(json)
                RecognitionMessage(
                    taskId = o.optString("task_id"),
                    isFinal = o.optBoolean("is_final"),
                    text = o.optString("text"),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
