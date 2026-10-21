package com.icegps.voiceime.util

import android.content.Context

/**
 * HARNESS-ONLY stub (not part of the app).
 * The pinyin scheme is switchable by the driver via [scheme].
 */
object Prefs {
    var scheme: String = "xiaohe"

    fun getPinyinScheme(ctx: Context): String = scheme

    fun isClipEnabled(ctx: Context): Boolean = true
}
