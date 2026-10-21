package com.icegps.voiceime.service.clip

/**
 * HARNESS-ONLY stub (not part of the app).
 * Only the surface PinyinInputController touches: addListener + list().size.
 */
object ClipStore {
    data class ClipEntry(val text: String)

    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) = listeners.add(l)

    fun list(): List<ClipEntry> = emptyList()
}
