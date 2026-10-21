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
        // 零声母（小鹤标准）：按键对 = 韵母首字母 + 该韵母的键位。
        // 主循环里 init="" 时取不到声母键（INITIAL_TO_KEY[""] 与 "".firstOrNull() 均为 null），
        // 零声母音节一个都建不进来，必须在这里统一补建。
        // 放行范围（能独立成音节的韵母）：
        //  · a/o/e 系（a、ai、an、ang、ao、e、ei、en、eng、er、o、ou）：PinyinSyllables.isValid(fin)
        //    按独立拼写可查到；其中单韵母 a/o/e 按本规则正好得 aa/oo/ee，与小鹤"单韵母双写"一致，
        //    故原先 a/o/e 的特殊处理已被本规则覆盖、删除。
        //  · i 系（ia、ie、iao、ian、iang、in、ing、iong）与 ong：全拼正字法里不独立拼写
        //    （i 系零声母写作 y- 形式、ong 必带声母），isValid 按独立拼写查不到，但小鹤零声母
        //    同样以韵母首字母作声母键，故显式放行；与既有声母对并存（一对多，如 is=[chong, iong]）。
        // u 系（ua、uo、uai、uan、uang 等）不能独立成音节、且首键 u 与 sh 的键冲突，不放行，
        // 避免污染既有声母对（如 uu 不得给 shu 加假音节）。
        val extraZeroInitial = setOf("ia", "ie", "iao", "ian", "iang", "in", "ing", "iong", "ong")
        for (fin in finals) {
            if (!PinyinSyllables.isValid(fin) && fin !in extraZeroInitial) continue
            val finKey = FINAL_TO_KEY[fin] ?: continue
            val pair = "${fin.first()}$finKey"
            val list = result.getOrPut(pair) { mutableListOf() }
            if (fin !in list) list.add(fin)
        }
        // 零声母双字母韵母"照字母打"（V86，与上面的标准键位打法并存）：
        // 按键对 = 韵母本身的两个字母（安 an 就打 a、n 两键）。
        // 只加双字母韵母——三字母（如 ang）照打要三个键，会破坏"每音节两键"的切分。
        // ai/ei/ao/ou/an/en 的首键 a/e/o 永不做声母键，这些字面对原先为空，纯新增零冲突；
        // er 的字面对与标准对相同（下面去重）；in 的字面对 "in" 与 iao 的标准对
        // （i + iao键n）是同一个对，追加在 iao 之后并存、候选合并（同 is=[chong, iong] 模式）。
        for (fin in listOf("an", "ai", "ao", "ou", "en", "ei", "er", "in")) {
            val list = result.getOrPut(fin) { mutableListOf() }
            if (fin !in list) list.add(fin)
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
