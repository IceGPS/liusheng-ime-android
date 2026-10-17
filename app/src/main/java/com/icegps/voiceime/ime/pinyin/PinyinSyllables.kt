package com.icegps.voiceime.ime.pinyin

/**
 * 合法拼音音节表 + 全拼连续输入切分。
 *
 * 全拼输入如 "zhangliang" 需要切分为 ["zhang", "liang"]。
 * 用动态规划找所有合法切分，优先长词（配合词典做最大匹配）。
 */
object PinyinSyllables {
    /** 全部合法音节（不含声调） */
    private val VALID: Set<String> = setOf(
        "a","ai","an","ang","ao",
        "ba","bai","ban","bang","bao","bei","ben","beng","bi","bian","biao","bie","bin","bing","bo","bu",
        "ca","cai","can","cang","cao","ce","cen","ceng","cha","chai","chan","chang","chao","che","chen","cheng",
        "chi","chong","chou","chu","chua","chuai","chuan","chuang","chui","chun","chuo",
        "ci","cong","cou","cu","cuan","cui","cun","cuo",
        "da","dai","dan","dang","dao","de","dei","den","deng","di","dia","dian","diao","die","ding","diu",
        "dong","dou","du","duan","dui","dun","duo",
        "e","ei","en","eng","er",
        "fa","fan","fang","fei","fen","feng","fo","fou","fu",
        "ga","gai","gan","gang","gao","ge","gei","gen","geng","gong","gou","gu","gua","guai","guan","guang",
        "gui","gun","guo",
        "ha","hai","han","hang","hao","he","hei","hen","heng","hong","hou","hu","hua","huai","huan","huang",
        "hui","hun","huo",
        "ji","jia","jian","jiang","jiao","jie","jin","jing","jiong","jiu","ju","juan","jue","jun",
        "ka","kai","kan","kang","kao","ke","kei","ken","keng","kong","kou","ku","kua","kuai","kuan","kuang",
        "kui","kun","kuo",
        "la","lai","lan","lang","lao","le","lei","leng","li","lia","lian","liang","liao","lie","lin","ling",
        "liu","long","lou","lu","luan","lue","lun","luo","lv",
        "ma","mai","man","mang","mao","me","mei","men","meng","mi","mian","miao","mie","min","ming","miu",
        "mo","mou","mu",
        "na","nai","nan","nang","nao","ne","nei","nen","neng","ni","nian","niang","niao","nie","nin","ning",
        "niu","nong","nou","nu","nuan","nue","nun","nuo","nv",
        "o","ou",
        "pa","pai","pan","pang","pao","pei","pen","peng","pi","pian","piao","pie","pin","ping","po","pou","pu",
        "qi","qia","qian","qiang","qiao","qie","qin","qing","qiong","qiu","qu","quan","que","qun",
        "ran","rang","rao","re","ren","reng","ri","rong","rou","ru","ruan","rui","run","ruo",
        "sa","sai","san","sang","sao","se","sen","seng","sha","shai","shan","shang","shao","she","shei","shen",
        "sheng","shi","shou","shu","shua","shuai","shuan","shuang","shui","shun","shuo",
        "si","song","sou","su","suan","sui","sun","suo",
        "ta","tai","tan","tang","tao","te","teng","ti","tian","tiao","tie","ting","tong","tou","tu","tuan",
        "tui","tun","tuo",
        "wa","wai","wan","wang","wei","wen","weng","wo","wu",
        "xi","xia","xian","xiang","xiao","xie","xin","xing","xiong","xiu","xu","xuan","xue","xun",
        "ya","yan","yang","yao","ye","yi","yin","ying","yo","yong","you","yu","yuan","yue","yun",
        "za","zai","zan","zang","zao","ze","zei","zen","zeng","zha","zhai","zhan","zhang","zhao","zhe","zhei",
        "zhen","zheng","zhi","zhong","zhou","zhu","zhua","zhuai","zhuan","zhuang","zhui","zhun","zhuo",
        "zi","zong","zou","zu","zuan","zui","zun","zuo"
    )

    fun isValid(syllable: String): Boolean = syllable in VALID

    /**
     * 将连续拼音切分为音节列表。
     * 返回所有可能的切分（按总音节数降序，即优先长音节组合）。
     * 如 "xian" → [["xian"], ["xi","an"]]
     */
    fun segment(input: String): List<List<String>> {
        if (input.isEmpty()) return listOf(emptyList())
        val results = mutableListOf<List<String>>()
        fun dfs(pos: Int, current: MutableList<String>) {
            if (pos == input.length) {
                results.add(current.toList())
                return
            }
            // 尝试从长到短匹配（最长 6 个字母：zhuang/chuang/shuang）
            for (len in 6 downTo 1) {
                if (pos + len > input.length) continue
                val sub = input.substring(pos, pos + len)
                if (sub in VALID) {
                    current.add(sub)
                    dfs(pos + len, current)
                    current.removeAt(current.size - 1)
                }
            }
        }
        dfs(0, mutableListOf())
        // 按音节数升序（音节越少越优先，即更长的词）
        return results.sortedBy { it.size }
    }

    /**
     * 贪心最长匹配切分（用于快速候选）。
     * 返回一种切分。
     */
    fun greedySegment(input: String): List<String> {
        val result = mutableListOf<String>()
        var pos = 0
        while (pos < input.length) {
            var matched: String? = null
            for (len in 6 downTo 1) {
                if (pos + len > input.length) continue
                val sub = input.substring(pos, pos + len)
                if (sub in VALID) { matched = sub; break }
            }
            if (matched == null) {
                // 无法匹配，剩余部分作为原始输入
                result.add(input.substring(pos))
                break
            }
            result.add(matched)
            pos += matched.length
        }
        return result
    }
}
