package com.icegps.voiceime.ime.pinyin

/**
 * 拼音引擎桩实现（第一阶段，仅用于演示 UI plumbing）。
 *
 * - processKey：拼接字母
 * - getComposingText：贪心最长匹配切分音节，用 ' 分隔（如 "ni'hao"）
 * - getCandidates：查演示词表，命中整串优先，否则按首音节给单字候选
 *
 * 第二阶段替换为 RIME JNI 实现后删除此类。
 */
class StubPinyinEngine : PinyinEngine {

    private val sb = StringBuilder()
    private var mode: PinyinMode = PinyinMode.QUANPIN

    override fun processKey(ch: Char) {
        // 桩实现只接受小写字母，其它字符忽略
        if (ch in 'a'..'z') sb.append(ch)
    }

    override fun deleteLast() {
        if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
    }

    override fun clear() {
        sb.clear()
    }

    override fun getComposingText(): String = segment(sb.toString())

    override fun rawInput(): String = sb.toString()

    override fun getCandidates(): List<String> {
        val raw = sb.toString()
        if (raw.isEmpty()) return emptyList()
        // 整串命中演示词（如 "nihao" -> 你好）
        DEMO_WORDS[raw]?.let { return it }
        // 否则按首音节给单字候选，保证总有可点的东西
        val firstSyllable = segment(raw).split("'").firstOrNull() ?: raw
        return (DEMO_WORDS[firstSyllable] ?: listOf(firstSyllable)).take(6)
    }

    override fun selectCandidate(index: Int): String? {
        val word = getCandidates().getOrNull(index) ?: return null
        sb.clear()
        return word
    }

    override fun setMode(mode: PinyinMode) {
        this.mode = mode
    }

    /**
     * 贪心最长匹配切分音节（演示用）。
     * RIME 接入后由引擎负责真正的切分与转换。
     */
    private fun segment(s: String): String {
        if (s.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            var matched: String? = null
            var len = minOf(6, s.length - i) // 最长音节 6 字母（如 zhuang）
            while (len >= 1) {
                val sub = s.substring(i, i + len)
                if (sub in SYLLABLES) {
                    matched = sub
                    break
                }
                len--
            }
            if (matched != null) {
                parts.add(matched)
                i += matched.length
            } else {
                // 兜底：无法成音节的字母单独成段（如输入中的半截 "nih"）
                parts.add(s[i].toString())
                i++
            }
        }
        return parts.joinToString("'")
    }

    companion object {
        /** 演示候选词表 */
        private val DEMO_WORDS: Map<String, List<String>> = mapOf(
            "ni" to listOf("你", "尼", "呢", "泥", "拟", "逆"),
            "hao" to listOf("好", "号", "毫", "浩", "耗", "郝"),
            "nihao" to listOf("你好"),
            "zhong" to listOf("中", "种", "重", "钟", "众"),
            "guo" to listOf("国", "果", "过", "郭", "锅"),
            "zhongguo" to listOf("中国"),
            "wo" to listOf("我", "握", "窝", "沃"),
            "shi" to listOf("是", "时", "事", "使", "式", "十"),
            "de" to listOf("的", "得", "德"),
            "zai" to listOf("在", "再", "载"),
            "jian" to listOf("见", "件", "建", "间", "键"),
            "men" to listOf("们", "门"),
            "ma" to listOf("吗", "妈", "马", "骂"),
        )

        /** 演示用常用音节表（贪心切分） */
        private val SYLLABLES: Set<String> = setOf(
            "a", "ai", "an", "ang", "ao",
            "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi", "bian", "biao",
            "bie", "bin", "bing", "bo", "bu",
            "ca", "cai", "can", "cang", "cao", "ce", "cen", "ceng", "cha", "chai",
            "chan", "chang", "chao", "che", "chen", "cheng", "chi", "chong", "chou", "chu",
            "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo", "ci", "cong", "cou",
            "cu", "cuan", "cui", "cun", "cuo",
            "da", "dai", "dan", "dang", "dao", "de", "dei", "den", "deng", "di", "dia",
            "dian", "diao", "die", "ding", "diu", "dong", "dou", "du", "duan", "dui",
            "dun", "duo",
            "e", "ei", "en", "eng", "er",
            "fa", "fan", "fang", "fei", "fen", "feng", "fo", "fou", "fu",
            "ga", "gai", "gan", "gang", "gao", "ge", "gei", "gen", "geng",
            "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo",
            "ha", "hai", "han", "hang", "hao", "he", "hei", "hen", "heng",
            "hong", "hou", "hu", "hua", "huai", "huan", "huang", "hui", "hun", "huo",
            "ji", "jia", "jian", "jiang", "jiao", "jie", "jin", "jing", "jiong", "jiu",
            "ju", "juan", "jue", "jun",
            "ka", "kai", "kan", "kang", "kao", "ke", "ken", "keng", "kong", "kou",
            "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
            "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia",
            "lian", "liang", "liao", "lie", "lin", "ling", "liu", "long", "lou", "lu",
            "luan", "lun", "luo", "lv", "lve",
            "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi",
            "mian", "miao", "mie", "min", "ming", "miu", "mo", "mou", "mu",
            "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen", "neng", "ni",
            "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu",
            "nuan", "nun", "nuo", "nv", "nve",
            "o", "ou",
            "pa", "pai", "pan", "pang", "pao", "pei", "pen", "peng", "pi", "pian",
            "piao", "pie", "pin", "ping", "po", "pou", "pu",
            "qi", "qia", "qian", "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu",
            "qu", "quan", "que", "qun",
            "ran", "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru",
            "ruan", "rui", "run", "ruo",
            "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai",
            "shan", "shang", "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu",
            "shua", "shuai", "shuan", "shuang", "shui", "shun", "shuo", "si", "song", "sou",
            "su", "suan", "sui", "sun", "suo",
            "ta", "tai", "tan", "tang", "tao", "te", "teng", "ti", "tian", "tiao",
            "tie", "ting", "tong", "tou", "tu", "tuan", "tui", "tun", "tuo",
            "wa", "wai", "wan", "wang", "wei", "wen", "weng", "wo", "wu",
            "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu",
            "xu", "xuan", "xue", "xun",
            "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong",
            "you", "yu", "yuan", "yue", "yun",
            "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen", "zeng", "zha",
            "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong",
            "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo",
            "zi", "zong", "zou", "zu", "zuan", "zui", "zun", "zuo",
        )
    }
}
