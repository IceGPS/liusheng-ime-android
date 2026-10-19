package com.icegps.voiceime.service.clip

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * 剪贴板历史条目。
 *
 * @param source "local" 表示本机复制；局域网同步来的标注来源设备名。
 */
data class ClipEntry(
    val id: Long,
    val text: String,
    val ts: Long,
    val source: String,
    val pinned: Boolean,
)

private class ClipDbHelper(ctx: Context) :
    SQLiteOpenHelper(ctx, "clip_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE clips(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "text TEXT NOT NULL," +
                "ts INTEGER NOT NULL," +
                "source TEXT NOT NULL DEFAULT 'local'," +
                "pinned INTEGER NOT NULL DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX idx_clips_ts ON clips(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

/**
 * 剪贴板历史本地存储（SQLite，v1）。
 *
 * - 只存文本；去重（同文本+同来源只保留一条，刷新时间戳顶到最前面）。
 * - 上限 50 条：超出后淘汰最旧的非置顶条目；置顶条目不参与淘汰。
 * - 写操作走单线程池，读操作同步；变更通过 listener 在主线程通知。
 */
object ClipStore {
    const val SOURCE_LOCAL = "local"
    const val MAX_ENTRIES = 50
    /** 单条文本截断长度（防超长内容撑爆数据库） */
    const val MAX_TEXT_LEN = 5000

    private var helper: ClipDbHelper? = null
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "clip-store").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun init(ctx: Context) {
        if (helper == null) helper = ClipDbHelper(ctx.applicationContext)
    }

    private fun db(): SQLiteDatabase =
        helper?.writableDatabase
            ?: throw IllegalStateException("ClipStore.init() 未调用")

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun notifyChanged() {
        mainHandler.post { listeners.toList().forEach { it() } }
    }

    /**
     * 记录一条剪贴板内容（异步写）。
     * 同文本+同来源已存在时只刷新时间戳，不产生重复条目。
     */
    fun add(rawText: String, source: String = SOURCE_LOCAL) {
        val text = rawText.take(MAX_TEXT_LEN)
        if (text.isBlank()) return
        io.execute {
            try {
                val db = db()
                val now = System.currentTimeMillis()
                // 去重：同文本+同来源 → 刷新时间戳
                db.rawQuery(
                    "SELECT id, pinned FROM clips WHERE text=? AND source=? LIMIT 1",
                    arrayOf(text, source),
                ).use { c ->
                    if (c.moveToFirst()) {
                        val id = c.getLong(0)
                        val cv = ContentValues().apply { put("ts", now) }
                        db.update("clips", cv, "id=?", arrayOf(id.toString()))
                        notifyChanged()
                        return@execute
                    }
                }
                val cv = ContentValues().apply {
                    put("text", text)
                    put("ts", now)
                    put("source", source)
                    put("pinned", 0)
                }
                db.insert("clips", null, cv)
                // 淘汰：总数超限时删最旧的非置顶
                db.rawQuery("SELECT COUNT(*) FROM clips", null).use { c ->
                    if (c.moveToFirst() && c.getInt(0) > MAX_ENTRIES) {
                        db.execSQL(
                            "DELETE FROM clips WHERE id IN (" +
                                "SELECT id FROM clips WHERE pinned=0 " +
                                "ORDER BY ts ASC LIMIT ?)",
                            arrayOf((c.getInt(0) - MAX_ENTRIES).toString()),
                        )
                    }
                }
                notifyChanged()
            } catch (_: Exception) {
            }
        }
    }

    /** 全部历史：置顶在前（按时间倒序），然后是未置顶（按时间倒序）。主线程调用。 */
    fun list(): List<ClipEntry> {
        val out = ArrayList<ClipEntry>()
        try {
            db().rawQuery(
                "SELECT id, text, ts, source, pinned FROM clips " +
                    "ORDER BY pinned DESC, ts DESC",
                null,
            ).use { c ->
                while (c.moveToNext()) {
                    out.add(
                        ClipEntry(
                            id = c.getLong(0),
                            text = c.getString(1) ?: "",
                            ts = c.getLong(2),
                            source = c.getString(3) ?: SOURCE_LOCAL,
                            pinned = c.getInt(4) != 0,
                        ),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun delete(id: Long) {
        io.execute {
            try {
                db().delete("clips", "id=?", arrayOf(id.toString()))
                notifyChanged()
            } catch (_: Exception) {
            }
        }
    }

    fun setPinned(id: Long, pinned: Boolean) {
        io.execute {
            try {
                val cv = ContentValues().apply { put("pinned", if (pinned) 1 else 0) }
                db().update("clips", cv, "id=?", arrayOf(id.toString()))
                notifyChanged()
            } catch (_: Exception) {
            }
        }
    }

    /** 清空：只清未置顶，保留用户置顶的常用条目。 */
    fun clearUnpinned() {
        io.execute {
            try {
                db().delete("clips", "pinned=0", null)
                notifyChanged()
            } catch (_: Exception) {
            }
        }
    }
}
