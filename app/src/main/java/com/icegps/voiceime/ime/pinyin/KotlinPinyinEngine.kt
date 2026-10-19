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

    /** 用户词典（自学习，V72；单例，init 幂等） */
    init {
        UserDict.init(context)
    }

    /** 造词链：连续单字点选 (词, 拼音)，最多 6 个 */
    private val recentSingles = ArrayDeque<Pair<String, String>>()

    /** 该词是否在个人词典中（候选栏长按删除菜单用） */
    fun isUserWord(word: String): Boolean = UserDict.contains(word)

    /** 从个人词典删除该词（所有拼音键） */
    fun deleteUserWord(word: String) = UserDict.deleteByWord(word)

    /** 打断造词链（如直接上屏空格/回车等非选词提交） */
    fun breakWordChain() {
        recentSingles.clear()
    }

    fun processKey(ch: Char) {
        buffer.append(ch)
    }

    fun deleteLast(): Boolean {
        if (buffer.isEmpty()) return false
        buffer.deleteCharAt(buffer.length - 1)
        recentSingles.clear() // 删除打断造词链
        return true
    }

    fun clear() {
        buffer.clear()
        recentSingles.clear()
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
     * 3.5 尾部不完整声母扩展：完整前缀词组 × 尾声母高频单字
     *     （如 "zhongguor" → 中国人/中国热…，而不只给首字候选）
     * 4. 兜底：输入无法切分音节时（如单个声母 "l"），按首字母给高频单字
     */
    fun getCandidates(maxCount: Int = 9): List<Candidate> {
        if (buffer.isEmpty()) return emptyList()

        // 0. 个人词典置顶（V72）：整串输入的规范拼音键精确命中用户词典时，
        //    按用户频次置于静态词库之前（freq>=2 或造词来源才参与，见 UserDict）
        val userTop = userRankCandidates()
        val userTopWords = userTop.map { it.word }.toSet()

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

        // 3.5 尾部不完整声母扩展：完整前缀词组 × 尾声母高频单字
        //     （如 "zhongguor" → 中国人/中国热…，而不只给首字候选）
        if (result.isEmpty()) {
            for (c in trailingInitialCandidates()) {
                result.getOrPut(c.word) { c }
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

        // 排序：消耗音节数多者优先，同级按词频；
        // 个人词典命中置于最前（已去重静态重复项）
        val staticSorted = result.values
            .sortedWith(compareByDescending<Candidate> { it.consumedSyllables }
                .thenByDescending { it.freq })
        if (userTop.isEmpty()) return staticSorted.take(maxCount)
        return (userTop + staticSorted.filter { it.word !in userTopWords }).take(maxCount)
    }

    /**
     * 个人词典置顶候选：当前整串输入的规范拼音键精确命中用户词典。
     * 选中的 Candidate 消耗整串音节数（选中即消费全部输入）。
     */
    private fun userRankCandidates(): List<Candidate> {
        val (key, sylCount) = fullPinyinKey() ?: return emptyList()
        if (key.isEmpty() || sylCount <= 0) return emptyList()
        return UserDict.lookupRanked(key).map { uw ->
            Candidate(uw.word, Int.MAX_VALUE, sylCount)
        }
    }

    /**
     * 当前整串输入的规范拼音键 + 音节数（跨全拼/双拼归一）。
     * 无法解析（如双拼落单键）时返回 null。
     */
    private fun fullPinyinKey(): Pair<String, Int>? {
        if (buffer.isEmpty()) return null
        return when (mode) {
            Mode.QUANPIN -> {
                val segs = PinyinSyllables.segment(buffer.toString()).firstOrNull()
                    ?: return null
                segs.joinToString("") to segs.size
            }
            Mode.XIAOHE -> {
                if (buffer.length % 2 != 0) return null
                val syls = mutableListOf<String>()
                var i = 0
                while (i < buffer.length) {
                    val s = ShuangpinMapper.keysToSyllables(buffer.substring(i, i + 2))
                    if (s.isEmpty()) return null
                    syls.add(s[0])
                    i += 2
                }
                syls.joinToString("") to syls.size
            }
        }
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

    /** 单字母拼音声母（含 y/w） */
    private fun isPinyinInitial(c: Char): Boolean = c in "bpmfdtnlgkhjqxrzcsyw"

    /**
     * "完整前缀 + 尾部不完整声母" 解析（如 "zhongguor" → [zhong, guo] + 'r'）。
     * 只处理尾部单个声母字母的情形（最常见：刚按下个音节的首字母）。
     */
    private fun parseWithTrailingInitial(): Pair<List<String>, Char>? {
        if (buffer.length < 2) return null
        return when (mode) {
            Mode.QUANPIN -> {
                val initial = buffer.last().lowercaseChar()
                if (!isPinyinInitial(initial)) return null
                val segs = PinyinSyllables.segment(buffer.substring(0, buffer.length - 1))
                if (segs.isEmpty()) null else segs[0] to initial
            }
            Mode.XIAOHE -> {
                // 双拼：偶数位是完整双拼，末位单键是下个音节的声母
                if (buffer.length % 2 == 0 || buffer.length < 3) return null
                val initial = xiaoheKeyToInitial(buffer.last())
                val pairPart = buffer.substring(0, buffer.length - 1)
                val groups = mutableListOf<String>()
                var i = 0
                while (i < pairPart.length) {
                    val syls = ShuangpinMapper.keysToSyllables(pairPart.substring(i, i + 2))
                    if (syls.isEmpty()) return null
                    groups.add(syls[0])
                    i += 2
                }
                groups to initial
            }
        }
    }

    /**
     * 尾部不完整声母扩展候选：完整前缀的词组 × 尾声母高频单字。
     * 如 "zhongguor" → 中国人/中国热…（consumedSyllables 按前缀音节数+1 计，
     * 排序时优先于纯前缀词组）。
     */
    private fun trailingInitialCandidates(): List<Candidate> {
        val (syllables, initial) = parseWithTrailingInitial() ?: return emptyList()
        val prefixWords = dict.lookup(syllables.joinToString("")).take(15)
        if (prefixWords.isEmpty()) return emptyList()
        val chars = dict.lookupByInitial(initial).take(8)
        if (chars.isEmpty()) return emptyList()
        val out = ArrayList<Candidate>(prefixWords.size * chars.size)
        for (w in prefixWords) {
            for (c in chars) {
                out.add(Candidate(w.word + c.word, w.freq, syllables.size + 1))
            }
        }
        return out
    }

    /**
     * 选中候选词：返回上屏文本，并从 buffer 中消费对应音节。
     *
     * @param learn 是否计入用户词典自学习（用户主动点选=true；
     *   内部自动上屏如切键盘时的首选提交=false；回车/语音不走这里）
     */
    fun selectCandidate(c: Candidate, learn: Boolean = true): String {
        if (c.consumeAll) {
            // 首字母兜底候选：整个输入缓冲一次消费完；不学习（输入不完整）
            buffer.clear()
            recentSingles.clear()
            return c.word
        }
        // 本次消费的规范拼音键（学习用），与排序融合的 fullPinyinKey 同源
        val consumedKey: String = when (mode) {
            Mode.QUANPIN -> {
                val segs = PinyinSyllables.segment(buffer.toString()).firstOrNull()
                    ?: run {
                        recentSingles.clear()
                        return c.word
                    }
                val n = minOf(c.consumedSyllables, segs.size)
                var consumed = 0
                for (i in 0 until n) consumed += segs[i].length
                val key = segs.take(n).joinToString("")
                buffer.delete(0, consumed)
                key
            }
            Mode.XIAOHE -> {
                val n = minOf(c.consumedSyllables, buffer.length / 2)
                val sb = StringBuilder()
                var ok = true
                for (i in 0 until n) {
                    val syls = ShuangpinMapper.keysToSyllables(buffer.substring(i * 2, i * 2 + 2))
                    if (syls.isEmpty()) {
                        ok = false
                        break
                    }
                    sb.append(syls[0])
                }
                val toDelete = minOf(c.consumedSyllables * 2, buffer.length)
                buffer.delete(0, toDelete)
                if (ok) sb.toString() else ""
            }
        }
        if (learn && c.word.isNotEmpty() && consumedKey.isNotEmpty()) {
            // 1. 选词记忆
            UserDict.learn(consumedKey, c.word, UserDict.Source.TAP)
            // 2. 造词：连续 2–6 个单字点选（单字+单音节），把字和拼音连起来学
            if (c.word.length == 1 && c.consumedSyllables == 1) {
                recentSingles.addLast(c.word to consumedKey)
                if (recentSingles.size > 6) recentSingles.removeFirst()
                if (recentSingles.size >= 2) {
                    val w = recentSingles.joinToString("") { it.first }
                    val p = recentSingles.joinToString("") { it.second }
                    UserDict.learn(p, w, UserDict.Source.COMPOSE)
                }
            } else {
                recentSingles.clear()
            }
        } else {
            // 非用户主动选择也打断造词链
            recentSingles.clear()
        }
        return c.word
    }
}
