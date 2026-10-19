package com.icegps.voiceime.ime.pinyin

/**
 * 纯 Kotlin 拼音引擎（RIME 数据：雾凇拼音词库 + 小鹤双拼标准映射）。
 *
 * 支持：
 * - 全拼：连续输入自动切分音节
 * - 小鹤双拼：每两键一音节
 * - 词组/整句：按词频排序候选
 * - 模糊音：默认关闭（用户明确要求）
 *
 * 用户词典（自学习）后续接入。
 */
class KotlinPinyinEngine(
    private val context: android.content.Context
) {
    enum class Mode { QUANPIN, XIAOHE }

    var mode: Mode = Mode.QUANPIN

    /** 原始按键序列 */
    private val buffer = StringBuilder()

    /** 词典（懒加载） */
    private val dict: PinyinDictionary by lazy { PinyinDictionary.get(context) }

    fun processKey(ch: Char) {
        buffer.append(ch)
    }

    fun deleteLast(): Boolean {
        if (buffer.isEmpty()) return false
        buffer.deleteCharAt(buffer.length - 1)
        return true
    }

    fun clear() {
        buffer.clear()
    }

    fun isEmpty(): Boolean = buffer.isEmpty()

    fun rawInput(): String = buffer.toString()

    /**
     * 拼音组合文本（显示用），如 "zhang'liang"。
     * 双拼模式下显示转换后的拼音。
     */
    fun getComposingText(): String {
        if (buffer.isEmpty()) return ""
        return when (mode) {
            Mode.QUANPIN -> {
                PinyinSyllables.greedySegment(buffer.toString()).joinToString("'")
            }
            Mode.XIAOHE -> {
                val sb = StringBuilder()
                var i = 0
                while (i < buffer.length) {
                    if (i + 1 < buffer.length) {
                        val syls = ShuangpinMapper.keysToSyllables(buffer.substring(i, i + 2))
                        if (syls.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append("'")
                            // 有多个候选音节时取第一个（后续可让用户选择）
                            sb.append(syls[0])
                        } else {
                            if (sb.isNotEmpty()) sb.append("'")
                            sb.append(buffer.substring(i, i + 2))
                        }
                        i += 2
                    } else {
                        // 落单的键：显示为原始按键
                        if (sb.isNotEmpty()) sb.append("'")
                        sb.append(buffer[i])
                        i += 1
                    }
                }
                sb.toString()
            }
        }
    }

    /**
     * 将当前输入解析为音节序列。
     * 返回 List<音节>，双拼模式下每个音节可能有多个候选（如多音字键位冲突）。
     */
    private fun parseSyllables(): List<List<String>> {
        if (buffer.isEmpty()) return emptyList()
        return when (mode) {
            Mode.QUANPIN -> {
                // 全拼：取最优切分（音节数最少的前3种）
                val segs = PinyinSyllables.segment(buffer.toString())
                if (segs.isEmpty()) return emptyList()
                segs.take(3)
            }
            Mode.XIAOHE -> {
                // 双拼：每两键一组，每组可能对应多个音节
                val groups = mutableListOf<List<String>>()
                var i = 0
                while (i < buffer.length) {
                    if (i + 1 < buffer.length) {
                        val syls = ShuangpinMapper.keysToSyllables(buffer.substring(i, i + 2))
                        if (syls.isEmpty()) return emptyList() // 无效组合
                        groups.add(syls)
                        i += 2
                    } else {
                        // 最后一个单键：可能是声母前缀，暂不生成候选
                        return emptyList()
                    }
                }
                // 笛卡尔积展开（限制组合数）
                var combos: List<List<String>> = listOf(emptyList())
                for (g in groups) {
                    val next = mutableListOf<List<String>>()
                    for (c in combos) {
                        for (s in g.take(3)) {
                            next.add(c + s)
                            if (next.size > 20) break
                        }
                        if (next.size > 20) break
                    }
                    combos = next
                    if (combos.size > 20) break
                }
                combos
            }
        }
    }

    data class Candidate(
        val word: String,
        val freq: Int,
        val consumedSyllables: Int,
        /** 首字母兜底候选：选中后清空整个输入缓冲 */
        val consumeAll: Boolean = false,
    )

    /**
     * 生成候选词列表。
     * 策略：
     * 1. 完整输入的词组/整句候选（按词频）
     * 2. 逐段缩短的词组候选
     * 3. 首音节的单字候选
     * 4. 兜底：输入无法切分音节时（如单个声母 "l"），按首字母给高频单字
     */
    fun getCandidates(maxCount: Int = 9): List<Candidate> {
        if (buffer.isEmpty()) return emptyList()
        val syllableOptions = parseSyllables()

        val result = LinkedHashMap<String, Candidate>() // 去重保序

        if (syllableOptions.isNotEmpty()) {
            for (syllables in syllableOptions) {
                // 1. 完整匹配
                val fullKey = syllables.joinToString("")
                for (e in dict.lookup(fullKey)) {
                    result.getOrPut(e.word) { Candidate(e.word, e.freq, syllables.size) }
                }
                // 2. 逐段缩短：从最长前缀开始
                for (len in syllables.size - 1 downTo 1) {
                    val prefixKey = syllables.take(len).joinToString("")
                    for (e in dict.lookup(prefixKey)) {
                        result.getOrPut(e.word) { Candidate(e.word, e.freq, len) }
                    }
                    if (result.size >= maxCount * 3) break
                }
                // 3. 首音节单字
                val firstKey = syllables[0]
                for (e in dict.lookup(firstKey)) {
                    if (e.word.length == 1) {
                        result.getOrPut(e.word) { Candidate(e.word, e.freq, 1) }
                    }
                }
                if (result.size >= maxCount * 3) break
            }
        }

        // 4. 兜底：无法切分（如单个声母）时，按首字母给高频单字动态提示
        if (result.isEmpty()) {
            val initial = when (mode) {
                Mode.QUANPIN -> buffer[0].lowercaseChar()
                Mode.XIAOHE -> xiaoheKeyToInitial(buffer[0])
            }
            for (e in dict.lookupByInitial(initial).take(maxCount)) {
                result.getOrPut(e.word) { Candidate(e.word, e.freq, 1, consumeAll = true) }
            }
        }

        // 排序：消耗音节数多者优先，同级按词频
        return result.values
            .sortedWith(compareByDescending<Candidate> { it.consumedSyllables }
                .thenByDescending { it.freq })
            .take(maxCount)
    }

    /**
     * 小鹤双拼首键 → 拼音首字母（用于首字母兜底候选）。
     * v/i/u 是 zh/ch/sh 的声母键，映射到 z/c/s 组（含 zh/ch/sh 音节）；
     * 其余键按自身字母处理（单键歧义时优先按声母解释）。
     */
    private fun xiaoheKeyToInitial(key: Char): Char = when (key.lowercaseChar()) {
        'v' -> 'z' // zh
        'i' -> 'c' // ch
        'u' -> 's' // sh
        else -> key.lowercaseChar()
    }

    /**
     * 选中候选词：返回上屏文本，并从 buffer 中消费对应音节。
     */
    fun selectCandidate(c: Candidate): String {
        if (c.consumeAll) {
            // 首字母兜底候选：整个输入缓冲一次消费完
            buffer.clear()
            return c.word
        }
        when (mode) {
            Mode.QUANPIN -> {
                // 计算消耗的原始按键数：前 consumedSyllables 个音节的总字母数
                val segs = PinyinSyllables.segment(buffer.toString()).firstOrNull()
                    ?: return c.word
                var consumed = 0
                for (i in 0 until minOf(c.consumedSyllables, segs.size)) {
                    consumed += segs[i].length
                }
                buffer.delete(0, consumed)
            }
            Mode.XIAOHE -> {
                // 双拼每音节 2 键
                val toDelete = minOf(c.consumedSyllables * 2, buffer.length)
                buffer.delete(0, toDelete)
            }
        }
        return c.word
    }
}
