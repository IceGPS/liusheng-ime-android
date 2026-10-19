package com.icegps.voiceime.ime.pinyin

/**
 * 小鹤双拼映射表（标准方案，与 RIME xiaohe_shuangpin 一致）。
 *
 * 声母：zh→v, ch→i, sh→u，其余声母对应自身键。
 * 韵母映射见 FINAL_TO_KEY。
 *
 * 用法：双拼按键序列每两个一组 (声母键, 韵母键) → 查表得完整拼音音节。
 * 例如：huan → h(声母h) + r(uan→r)；shuang → u(sh→u) + l(uang→l)。
 */
object ShuangpinMapper {
    /** 韵母 → 按键 */
    private val FINAL_TO_KEY: Map<String, Char> = mapOf(
        // 单韵母（自身）
        "a" to 'a', "o" to 'o', "e" to 'e', "i" to 'i', "u" to 'u',
        // ü 在不同语境下写作 v
        "v" to 'v', "ü" to 'v',
        // 复韵母
        "ai" to 'd', "ei" to 'w', "ui" to 'v', "ao" to 'c', "ou" to 'z',
        "iu" to 'q', "ie" to 'p', "ue" to 't', "ve" to 't', "üe" to 't',
        "er" to 'r',
        // 鼻韵母
        "an" to 'j', "en" to 'f', "in" to 'b', "un" to 'y',
        "ang" to 'h', "eng" to 'g', "ing" to 'k', "ong" to 's',
        // 复合
        "ia" to 'x', "ua" to 'x',
        "iao" to 'n', "ian" to 'm',
        "iang" to 'l', "uang" to 'l',
        "iong" to 's',
        "uai" to 'k', "uan" to 'r',
        "uo" to 'o'
    )

    /** 声母 → 按键（zh/ch/sh 特殊，其余为自身） */
    private val INITIAL_TO_KEY: Map<String, Char> = mapOf(
        "zh" to 'v', "ch" to 'i', "sh" to 'u'
    )

    /** 反查：(声母键, 韵母键) → 所有可能的完整音节 */
    private val keyPairToSyllables: Map<String, List<String>> by lazy {
        buildReverseMap()
    }

    /**
     * 预热：输入法启动时后台调用，提前建好反查表，
     * 避免首次双拼输入时懒加载卡顿。
     */
    fun warmUp() {
        keyPairToSyllables.size
    }

    private fun buildReverseMap(): Map<String, List<String>> {
        val result = HashMap<String, MutableList<String>>()
        // 所有合法声母
        val initials = listOf(
            "b","p","m","f","d","t","n","l","g","k","h",
            "j","q","x","zh","ch","sh","r","z","c","s","y","w",""
        )
        // 所有合法韵母
        val finals = FINAL_TO_KEY.keys.toList()
        for (init in initials) {
            for (fin in finals) {
                val syllable = init + fin
                if (!PinyinSyllables.isValid(syllable)) continue
                val initKey = INITIAL_TO_KEY[init] ?: init.firstOrNull() ?: continue
                val finKey = FINAL_TO_KEY[fin] ?: continue
                val pair = "$initKey$finKey"
                result.getOrPut(pair) { mutableListOf() }.add(syllable)
            }
        }
        // 零声母特殊处理：a→aa, o→oo, e→ee（小鹤标准）
        for (fin in listOf("a", "o", "e")) {
            val finKey = FINAL_TO_KEY[fin] ?: continue
            val pair = "$finKey$finKey"
            result.getOrPut(pair) { mutableListOf() }.add(fin)
        }
        return result
    }

    /** 双拼按键序列（如 "hr"）→ 可能的拼音音节列表（如 ["huan"]） */
    fun keysToSyllables(keys: String): List<String> {
        if (keys.length != 2) return emptyList()
        return keyPairToSyllables[keys] ?: emptyList()
    }

    /** 检查按键序列是否可能是有效的双拼前缀 */
    fun isValidPrefix(keys: String): Boolean {
        if (keys.isEmpty()) return true
        if (keys.length == 1) {
            // 单个键：可能是声母键
            return true
        }
        return keyPairToSyllables.containsKey(keys.takeLast(2))
    }
}
