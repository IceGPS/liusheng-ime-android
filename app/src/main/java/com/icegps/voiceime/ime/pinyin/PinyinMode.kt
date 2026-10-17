package com.icegps.voiceime.ime.pinyin

/**
 * 拼音方案。
 *
 * 第一阶段桩实现仅占位；第二阶段 RIME JNI 接入后真正生效。
 */
enum class PinyinMode {
    /** 全拼 */
    QUANPIN,

    /** 小鹤双拼 */
    XIAOHE_SHUANGPIN,
}
