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
    }

    override fun getComposingText(): String = engine.getComposingText()

    override fun getCandidates(): List<String> {
        return lastCandidates.map { it.word }
    }

    override fun selectCandidate(index: Int): String? {
        val c = lastCandidates.getOrNull(index) ?: return null
        val word = engine.selectCandidate(c)
        refreshCandidates()
        return word
    }

    override fun setMode(mode: PinyinMode) {
        engine.mode = when (mode) {
            PinyinMode.QUANPIN -> KotlinPinyinEngine.Mode.QUANPIN
            PinyinMode.XIAOHE_SHUANGPIN -> KotlinPinyinEngine.Mode.XIAOHE
        }
        engine.clear()
        lastCandidates = emptyList()
    }

    private fun refreshCandidates() {
        lastCandidates = if (engine.isEmpty()) emptyList()
        else engine.getCandidates()
    }
}
