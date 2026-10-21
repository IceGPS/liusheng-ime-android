package com.icegps.voiceime.ime.pinyin

import android.content.Context

/**
 * HARNESS-ONLY stubs (not part of the app).
 *
 * UserDict: empty store, same semantics as the eval-baseline stub, extended
 * with the recordBigram/nextWords surface the controller uses. Because the
 * store is empty, lookupRanked() is empty and contains() is false, exactly
 * like a fresh install.
 *
 * AssociationEngine: returns a fixed next-word list so the controller's
 * association state machine (V83/V84) can be exercised end-to-end.
 */
object UserDict {
    enum class Source { TAP, COMPOSE }

    data class UserWord(
        val pinyin: String = "",
        val word: String,
        val freq: Int = 0,
        val source: Source = Source.TAP,
    )

    fun init(ctx: Context) {}

    fun learn(pinyin: String, word: String, source: Source) {}

    fun lookupRanked(pinyin: String): List<UserWord> = emptyList()

    fun contains(word: String): Boolean = false

    fun deleteByWord(word: String) {}

    fun recordBigram(prev: String, word: String) {}

    fun nextWords(prev: String): List<String> = emptyList()
}

object AssociationEngine {
    /** Non-empty on purpose: lets scenario E enter association mode. */
    fun nextWords(prev: String): List<String> = listOf("的", "了")
}
