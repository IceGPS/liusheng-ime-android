package com.icegps.voiceime.ime.pinyin

import android.content.Context
import java.io.DataInputStream

/**
 * 静态联想表（V83）：离线挖掘的相邻词共现（bigram），选词上屏后预测"下一词"。
 *
 * 数据：assets/assoc_dict.bin，由 tools/build_assoc.py 从 rime-ice 词库生成
 * （短语词条前向最大匹配分词 → 相邻词共现统计 → 每词 top8；另含手工高频搭配兜底）。
 *
 * 二进制格式（大端）：magic "ASOC" + version(4B) + entry_count(4B)；
 * 每条：prev_len(1B) + prev(utf8) + next_count(1B) + 每下一词 len(1B)+utf8。
 *
 * 加载：VoiceImeService 启动预热线程调用 [warmUp]；查询时未加载返回空
 * （个人联想层仍可独立工作），不阻塞 UI 线程做 IO。
 */
object AssociationEngine {

    private const val ASSET_NAME = "assoc_dict.bin"
    private const val MAGIC = "ASOC"

    @Volatile
    private var table: Map<String, List<String>>? = null

    /** 预热加载（后台线程调用；幂等）。解析失败静默降级为空表。 */
    fun warmUp(ctx: Context) {
        if (table != null) return
        synchronized(this) {
            if (table != null) return
            table = try {
                parse(ctx.applicationContext)
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    /** 静态联想：prev 的下一词候选（已按共现强度排序，最多 8 个）；未加载/无记录返回空 */
    fun nextWords(prev: String): List<String> {
        if (prev.isEmpty()) return emptyList()
        return table?.get(prev) ?: emptyList()
    }

    private fun parse(ctx: Context): Map<String, List<String>> {
        ctx.assets.open(ASSET_NAME).use { raw ->
            val dis = DataInputStream(raw.buffered())
            val magic = ByteArray(4)
            dis.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == MAGIC) { "bad magic" }
            dis.readInt() // version，当前只支持 1，读掉不校验细节
            val count = dis.readInt()
            val map = HashMap<String, List<String>>(count * 2)
            repeat(count) {
                val prevLen = dis.readUnsignedByte()
                val prevBytes = ByteArray(prevLen)
                dis.readFully(prevBytes)
                val prev = String(prevBytes, Charsets.UTF_8)
                val n = dis.readUnsignedByte()
                val list = ArrayList<String>(n)
                repeat(n) {
                    val len = dis.readUnsignedByte()
                    val b = ByteArray(len)
                    dis.readFully(b)
                    list.add(String(b, Charsets.UTF_8))
                }
                map[prev] = list
            }
            return map
        }
    }
}
