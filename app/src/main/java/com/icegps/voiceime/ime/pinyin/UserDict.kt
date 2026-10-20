package com.icegps.voiceime.ime.pinyin

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private class UserDictDbHelper(ctx: Context) :
    SQLiteOpenHelper(ctx, "user_dict.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE user_words(" +
                "pinyin TEXT NOT NULL," +
                "word TEXT NOT NULL," +
                "freq INTEGER NOT NULL DEFAULT 1," +
                "source TEXT NOT NULL DEFAULT 'TAP'," +
                "updated INTEGER NOT NULL," +
                "PRIMARY KEY (pinyin, word))",
        )
        db.execSQL("CREATE INDEX idx_user_words_word ON user_words(word)")
        db.execSQL(CREATE_BIGRAMS)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // V83（DB v2）：新增个人联想表 user_bigrams
        if (oldVersion < 2) db.execSQL(CREATE_BIGRAMS_IF_NOT_EXISTS)
    }

    companion object {
        private const val CREATE_BIGRAMS =
            "CREATE TABLE user_bigrams(" +
                "prev_word TEXT NOT NULL," +
                "word TEXT NOT NULL," +
                "freq INTEGER NOT NULL DEFAULT 1," +
                "updated INTEGER NOT NULL," +
                "PRIMARY KEY (prev_word, word))"
        private const val CREATE_BIGRAMS_IF_NOT_EXISTS =
            "CREATE TABLE IF NOT EXISTS user_bigrams(" +
                "prev_word TEXT NOT NULL," +
                "word TEXT NOT NULL," +
                "freq INTEGER NOT NULL DEFAULT 1," +
                "updated INTEGER NOT NULL," +
                "PRIMARY KEY (prev_word, word))"
    }
}

/**
 * 用户词典（自学习，V72）。
 *
 * 本地 SQLite，不联网。记录用户主动点选的候选词 (拼音键 → 词) 及频次，
 * 用于候选排序置顶；连续点选单字自动造词。
 *
 * 单例（仿 ClipStore）：引擎和设置页共用同一实例，内存缓存一致。
 * 使用前调 [init]（幂等）。
 *
 * 防"一次点错、永远置顶"（Hongjie 要求）：
 * - 选词记忆（TAP）需 freq >= [RANK_MIN_FREQ] 才参与置顶；freq=1 只记录不置顶
 * - 造词（COMPOSE，连续主动点选）误触概率低，立即生效
 * - 候选栏长按可删词；设置页可逐条管理；删除后可重新学习（v1 无黑名单）
 */
object UserDict {

    /** 学习来源：TAP=点选候选词；COMPOSE=连续点选单字造词 */
    enum class Source { TAP, COMPOSE }

    data class UserWord(
        val pinyin: String,
        val word: String,
        val freq: Int,
        val source: Source,
    )

    /** 选词记忆参与置顶的最低频次；造词（COMPOSE）不受此限 */
    const val RANK_MIN_FREQ = 2
    private const val MAX_WORD_LEN = 12

    private var helper: UserDictDbHelper? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "user-dict").apply { isDaemon = true }
    }

    /** 内存缓存：pinyin -> 词表（读走内存，UI 线程无 IO） */
    private val cache = ConcurrentHashMap<String, MutableList<UserWord>>()

    /** 个人联想缓存（V83）：prev_word -> (word, freq) 列表，按 freq 降序 */
    private val bigramCache = ConcurrentHashMap<String, MutableList<Pair<String, Int>>>()

    @Volatile private var loaded = false

    /** 幂等初始化 */
    fun init(ctx: Context) {
        if (helper == null) helper = UserDictDbHelper(ctx.applicationContext)
    }

    private fun db(): SQLiteDatabase =
        helper?.writableDatabase
            ?: throw IllegalStateException("UserDict.init() 未调用")

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                db().rawQuery(
                    "SELECT pinyin, word, freq, source FROM user_words",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        val p = c.getString(0)
                        val src = try {
                            Source.valueOf(c.getString(3))
                        } catch (_: Exception) {
                            Source.TAP
                        }
                        val w = UserWord(p, c.getString(1), c.getInt(2), src)
                        cache.getOrPut(p) { mutableListOf() }.add(w)
                    }
                }
                for ((_, list) in cache) list.sortByDescending { it.freq }
                db().rawQuery(
                    "SELECT prev_word, word, freq FROM user_bigrams",
                    null,
                ).use { c ->
                    while (c.moveToNext()) {
                        bigramCache.getOrPut(c.getString(0)) { mutableListOf() }
                            .add(c.getString(1) to c.getInt(2))
                    }
                }
                for ((_, list) in bigramCache) list.sortByDescending { it.second }
            } catch (_: Exception) {
            }
            loaded = true
        }
    }

    /**
     * 学习一条 (拼音键 → 词)。内存立即更新，DB 异步写。
     * 同一 (拼音, 词) 频次+1；source 取首次学习来源。
     */
    fun learn(pinyin: String, word: String, source: Source) {
        if (pinyin.isEmpty() || word.isEmpty() || word.length > MAX_WORD_LEN) return
        ensureLoaded()
        val srcName: String
        synchronized(this) {
            val list = cache.getOrPut(pinyin) { mutableListOf() }
            val idx = list.indexOfFirst { it.word == word }
            srcName = if (idx >= 0) {
                val old = list[idx]
                list[idx] = old.copy(freq = old.freq + 1)
                old.source.name
            } else {
                list.add(UserWord(pinyin, word, 1, source))
                source.name
            }
            list.sortByDescending { it.freq }
        }
        io.execute {
            try {
                val now = System.currentTimeMillis()
                db().execSQL(
                    "INSERT INTO user_words(pinyin, word, freq, source, updated) " +
                        "VALUES(?,?,?,?,?) " +
                        "ON CONFLICT(pinyin, word) DO UPDATE SET freq=freq+1, updated=?",
                    arrayOf(pinyin, word, 1, srcName, now, now),
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 置顶查询：freq >= [RANK_MIN_FREQ] 或造词来源，按频次降序。
     * 引擎用此结果把用户词置于静态词库之前。
     */
    fun lookupRanked(pinyin: String): List<UserWord> {
        ensureLoaded()
        return cache[pinyin]
            ?.filter { it.freq >= RANK_MIN_FREQ || it.source == Source.COMPOSE }
            ?.sortedByDescending { it.freq }
            ?: emptyList()
    }

    /** 该词是否在个人词典中（任意频次）——长按删除菜单的 eligibility */
    fun contains(word: String): Boolean {
        if (word.isEmpty()) return false
        ensureLoaded()
        return cache.values.any { list -> list.any { it.word == word } }
    }

    /** 按词删除（所有拼音键）。删除后不再参与置顶；之后再选可重新学习。 */
    fun deleteByWord(word: String) {
        if (word.isEmpty()) return
        ensureLoaded()
        synchronized(this) {
            for ((_, list) in cache) list.removeAll { it.word == word }
            cache.entries.removeAll { it.value.isEmpty() }
        }
        io.execute {
            try {
                db().delete("user_words", "word=?", arrayOf(word))
            } catch (_: Exception) {
            }
        }
    }

    /** 全部条目（设置页展示），按频次降序 */
    fun listAll(): List<UserWord> {
        ensureLoaded()
        return cache.values.flatten().sortedByDescending { it.freq }
    }

    fun count(): Int {
        ensureLoaded()
        return cache.values.sumOf { it.size }
    }

    fun clear() {
        ensureLoaded()
        synchronized(this) { cache.clear() }
        io.execute {
            try {
                db().delete("user_words", null, null)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- 个人联想（V83）：相邻词对 ----------

    /**
     * 记录一次真实上屏的相邻词对（prev → word）。内存立即更新，DB 异步写。
     * 联想点选上屏的词也走这里记录，联想链本身参与学习。
     * 注：词典管理页（清空/删词）本版不动这张表。
     */
    fun recordBigram(prev: String, word: String) {
        if (prev.isEmpty() || word.isEmpty()) return
        if (prev.length > MAX_WORD_LEN || word.length > MAX_WORD_LEN) return
        ensureLoaded()
        synchronized(this) {
            val list = bigramCache.getOrPut(prev) { mutableListOf() }
            val idx = list.indexOfFirst { it.first == word }
            if (idx >= 0) {
                list[idx] = word to (list[idx].second + 1)
            } else {
                list.add(word to 1)
            }
            list.sortByDescending { it.second }
        }
        io.execute {
            try {
                val now = System.currentTimeMillis()
                db().execSQL(
                    "INSERT INTO user_bigrams(prev_word, word, freq, updated) " +
                        "VALUES(?,?,1,?) " +
                        "ON CONFLICT(prev_word, word) DO UPDATE SET freq=freq+1, updated=?",
                    arrayOf(prev, word, now, now),
                )
            } catch (_: Exception) {
            }
        }
    }

    /** 个人联想：prev 的下一词候选（按个人频次降序；freq=1 也返回，靠后由合并顺序决定） */
    fun nextWords(prev: String): List<String> {
        if (prev.isEmpty()) return emptyList()
        ensureLoaded()
        return bigramCache[prev]
            ?.sortedByDescending { it.second }
            ?.map { it.first }
            ?: emptyList()
    }
}
