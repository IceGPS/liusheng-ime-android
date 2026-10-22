package com.icegps.voiceime.util

/**
 * 字→无调拼音表（语音纠错同音判定用）：基于 assets/char_pinyin.tsv
 * （由 tools/pinyin-data/gen_char_pinyin.py 从 Rime 8105 字表生成）。
 *
 * 纯逻辑、无 Android 依赖（资产读取在调用方），可直接 JVM 测试。
 * 非汉字字符（字母/数字/符号）的"读音"记为其自身小写形式，
 * 因此只有与汉字同拼音或与自身完全一致时才可能判同音。
 */
class CharPinyinTable(private val map: Map<Char, List<String>>) {

    /** 某字可能的读音（无数据时 = 自身小写）。 */
    fun readingsOf(ch: Char): List<String> =
        map[ch] ?: listOf(ch.lowercaseChar().toString())

    /**
     * 一段文本所有可能的读音序列集合（多音字笛卡尔积，规模上限 256 截断）。
     * 序列内以空格连接，如 "wang gong"。
     */
    fun sequences(text: String): Set<String> {
        var acc: Set<String> = setOf("")
        for (ch in text) {
            val next = HashSet<String>()
            for (prefix in acc) {
                for (r in readingsOf(ch)) {
                    next.add(if (prefix.isEmpty()) r else "$prefix $r")
                    if (next.size >= MAX_SEQUENCES) break
                }
                if (next.size >= MAX_SEQUENCES) break
            }
            acc = next
        }
        return acc
    }

    /**
     * 判定 before/after 是否同音，并给出展示用拼音：
     * 同音时两侧展示共同读音序列；不同音时各展示首个候选序列。
     * @return Triple(是否同音, before拼音, after拼音)
     */
    fun judge(before: String, after: String): Triple<Boolean, String, String> {
        val sa = sequences(before)
        val sb = sequences(after)
        val common = sa.intersect(sb).sorted()
        return if (common.isNotEmpty()) {
            Triple(true, common.first(), common.first())
        } else {
            Triple(false, sa.sorted().firstOrNull() ?: "", sb.sorted().firstOrNull() ?: "")
        }
    }

    companion object {
        private const val MAX_SEQUENCES = 256

        /** 从 TSV 行构建（每行 "字\t读音1,读音2,..."）。 */
        fun fromTsv(lines: List<String>): CharPinyinTable {
            val m = HashMap<Char, List<String>>()
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val ch = line[0]
                val readings = line.substring(tab + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (readings.isNotEmpty()) m[ch] = readings
            }
            return CharPinyinTable(m)
        }
    }
}
