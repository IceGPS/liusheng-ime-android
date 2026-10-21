package com.icegps.voiceime.ime.pinyin

/**
 * KotlinPinyinEngine → PinyinEngine 接口适配器。
 *
 * 把真实引擎（词典 + 小鹤映射 + 音节切分）接到 UI 定义的接口上。
 */
class EngineAdapter(
    context: android.content.Context
) : PinyinEngine {

    private val engine = KotlinPinyinEngine(context)

    /** 最近一次的候选列表（用于 index → Candidate 映射） */
    private var lastCandidates: List<KotlinPinyinEngine.Candidate> = emptyList()

    /** 全量候选列表（浮层面板用，不截断） */
    private var allCandidates: List<KotlinPinyinEngine.Candidate> = emptyList()

    companion object {
        /**
         * 浮层面板的全量候选上限（V87）。
         * 词库修复后单键候选最多 141 个（yi 键单字），多音节输入合并多个键的
         * 候选池后仍须保证词库内每个候选都能在全量面板翻到，故上限须远高于
         * 单键最大值；1024 覆盖合并池的现实规模，排序成本可忽略。
         * 候选栏预览仍只取前 9 个（getCandidates 默认）。
         */
        private const val MAX_ALL_CANDIDATES = 1024
    }

    override fun processKey(ch: Char) {
        engine.processKey(ch)
        refreshCandidates()
    }

    override fun deleteLast() {
        engine.deleteLast()
        refreshCandidates()
    }

    override fun clear() {
        engine.clear()
        lastCandidates = emptyList()
        allCandidates = emptyList()
    }

    override fun getComposingText(): String = engine.getComposingText()

    override fun rawInput(): String = engine.rawInput()

    override fun getCandidates(): List<String> {
        return lastCandidates.map { it.word }
    }

    override fun getAllCandidates(): List<String> {
        return allCandidates.map { it.word }
    }

    override fun selectCandidate(index: Int, learn: Boolean): String? {
        // 用全量列表做下标映射，兼容浮层面板的绝对下标（前9个顺序与 lastCandidates 一致）
        val c = allCandidates.getOrNull(index) ?: return null
        val word = engine.selectCandidate(c, learn)
        refreshCandidates()
        return word
    }

    /** 该词是否在个人词典中（候选栏长按删除菜单用） */
    fun isUserWord(word: String): Boolean = engine.isUserWord(word)

    /** 从个人词典删除该词（所有拼音键），刷新候选 */
    fun deleteUserWord(word: String) {
        engine.deleteUserWord(word)
        refreshCandidates()
    }

    override fun breakWordChain() = engine.breakWordChain()

    override fun setMode(mode: PinyinMode) {
        engine.mode = when (mode) {
            PinyinMode.QUANPIN -> KotlinPinyinEngine.Mode.QUANPIN
            PinyinMode.XIAOHE_SHUANGPIN -> KotlinPinyinEngine.Mode.XIAOHE
        }
        engine.clear()
        lastCandidates = emptyList()
        allCandidates = emptyList()
    }

    private fun refreshCandidates() {
        if (engine.isEmpty()) {
            lastCandidates = emptyList()
            allCandidates = emptyList()
        } else {
            // 候选栏用前9个；浮层用全量（仅多传一个 maxCount 参数，生成逻辑不动）
            lastCandidates = engine.getCandidates()
            allCandidates = engine.getCandidates(MAX_ALL_CANDIDATES)
        }
    }
}
