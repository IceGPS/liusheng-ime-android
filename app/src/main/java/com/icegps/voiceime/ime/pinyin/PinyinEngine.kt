package com.icegps.voiceime.ime.pinyin

/**
 * 拼音转换引擎接口。
 *
 * 第一阶段使用 [StubPinyinEngine] 桩实现（仅拼接字母、返回演示候选），
 * 第二阶段替换为 RIME JNI 实现（词典 + n-gram 整句转换）。
 */
interface PinyinEngine {
    /** 输入一个字母（已转为小写） */
    fun processKey(ch: Char)

    /** 删除最后一个拼音字符 */
    fun deleteLast()

    /** 清空当前拼音串 */
    fun clear()

    /** 拼音组合文本，如 "ni'hao"（用于 setComposingText 显示） */
    fun getComposingText(): String

    /** 用户实际键入的原始字母序列（双拼模式下未经全拼转换，如 "vk"） */
    fun rawInput(): String

    /** 当前候选词列表 */
    fun getCandidates(): List<String>

    /**
     * 全部候选词（不截断，用于浮层候选面板）。
     * 默认实现返回 [getCandidates]；真实引擎应返回全量列表。
     */
    fun getAllCandidates(): List<String> = getCandidates()

    /**
     * 选中第 index 个候选词。
     * @return 上屏文本；无候选时返回 null。选中后引擎内部清空拼音串。
     */
    fun selectCandidate(index: Int): String?

    /** 切换拼音方案：全拼 / 小鹤双拼 */
    fun setMode(mode: PinyinMode)
}
