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
    private val entries: Map<String, List<WordEntry>>
) {
    data class WordEntry(val word: String, val freq: Int)

    fun lookup(pinyinKey: String): List<WordEntry> {
        return entries[pinyinKey] ?: emptyList()
    }

    fun size(): Int = entries.size

    companion object {
        @Volatile private var instance: PinyinDictionary? = null

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
            return PinyinDictionary(map)
        }
    }
}
