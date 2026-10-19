package com.icegps.voiceime.ime.pinyin

import android.content.Context
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 拼音词典：从 assets/pinyin_dict.bin 加载。
 *
 * 二进制格式（见 tools/pinyin-data/preprocess_dict.py）：
 * - magic "PYDI" (4B) + version u32 + entry_count u32
 * - 每个条目：key_len u16 + key(utf8) + word_count u16 +
 *              每个词：word_len u16 + word(utf8) + freq u32
 *
 * 按拼音键（无空格音节串，如 "nihao"）查候选词，按词频降序。
 */
class PinyinDictionary private constructor(
    private val entries: Map<String, List<WordEntry>>,
    /** 首字母 → 高频单字（按词频降序），用于单字母输入时的动态提示 */
    private val initialIndex: Map<Char, List<WordEntry>>,
) {
    data class WordEntry(val word: String, val freq: Int)

    fun lookup(pinyinKey: String): List<WordEntry> {
        return entries[pinyinKey] ?: emptyList()
    }

    /**
     * 按拼音首字母查高频单字（如 'l' → 了/里/来/老…）。
     * 每个首字母最多返回 [INITIAL_TOP_N] 个。
     */
    fun lookupByInitial(initial: Char): List<WordEntry> {
        return initialIndex[initial.lowercaseChar()] ?: emptyList()
    }

    fun size(): Int = entries.size

    companion object {
        @Volatile private var instance: PinyinDictionary? = null

        /** 每个首字母保留的高频单字数 */
        private const val INITIAL_TOP_N = 60

        fun get(context: Context): PinyinDictionary {
            return instance ?: synchronized(this) {
                instance ?: load(context.applicationContext).also { instance = it }
            }
        }

        private fun load(context: Context): PinyinDictionary {
            val map = HashMap<String, List<WordEntry>>(800_000)
            context.assets.open("pinyin_dict.bin").use { ins ->
                val data = ins.readBytes()
                val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val magic = ByteArray(4).also { buf.get(it) }
                require(magic.decodeToString() == "PYDI") { "词典 magic 错误" }
                buf.int // version
                val count = buf.int
                repeat(count) {
                    val keyLen = buf.short.toInt() and 0xFFFF
                    val keyBytes = ByteArray(keyLen).also { buf.get(it) }
                    val key = keyBytes.decodeToString()
                    val wordCount = buf.short.toInt() and 0xFFFF
                    val words = ArrayList<WordEntry>(wordCount)
                    repeat(wordCount) {
                        val wLen = buf.short.toInt() and 0xFFFF
                        val wBytes = ByteArray(wLen).also { buf.get(it) }
                        val freq = buf.int
                        words.add(WordEntry(wBytes.decodeToString(), freq))
                    }
                    map[key] = words
                }
            }
            return PinyinDictionary(map, buildInitialIndex(map))
        }

        /**
         * 构建首字母 → 高频单字索引。
         * 遍历全部拼音键，取首字母；每个单字在该首字母下保留最大词频，
         * 按词频降序取前 INITIAL_TOP_N 个。
         */
        private fun buildInitialIndex(
            entries: Map<String, List<WordEntry>>
        ): Map<Char, List<WordEntry>> {
            val tmp = HashMap<Char, MutableMap<String, Int>>()
            for ((key, words) in entries) {
                if (key.isEmpty()) continue
                val initial = key[0]
                if (initial !in 'a'..'z') continue
                val bucket = tmp.getOrPut(initial) { HashMap() }
                for (w in words) {
                    if (w.word.length != 1) continue
                    val old = bucket[w.word]
                    if (old == null || w.freq > old) bucket[w.word] = w.freq
                }
            }
            return tmp.mapValues { (_, m) ->
                m.entries.sortedByDescending { it.value }
                    .take(INITIAL_TOP_N)
                    .map { WordEntry(it.key, it.value) }
            }
        }
    }
}
