package com.icegps.voiceime.ime.pinyin

import android.content.Context
import android.view.View
import com.icegps.voiceime.service.clip.ClipStore

/**
 * HARNESS-ONLY stand-ins (not part of the app).
 *
 * Same class names / member surface as the real views, so the VERBATIM
 * PinyinInputController compiles and runs against them on a plain JVM.
 * They only record state; all input-driving goes through the Listener
 * objects the controller installs, exactly like on device.
 */

class PinyinKeyboardView(context: Context = Context()) : View(context) {
    interface Listener {
        fun onLetter(ch: Char)
        fun onSymbol(sym: Char)
        fun onBackspace()
        fun onEnter()
        fun onShift()
        fun onLangToggle()
        fun onSymbolPage()
        fun onDigitPage()
        fun onSpace()
        fun onVoiceMode()
        fun onVoiceRecordStart()
        fun onVoiceRecordStop()
    }

    var listener: Listener? = null
    var isChineseMode: Boolean = true
    var enterLabel: String = "⏎"
}

class CandidateBarView(context: Context = Context()) : View(context) {
    var onOpenSettings: (() -> Unit)? = null
    var onClipboardClick: (() -> Unit)? = null
    var onLangToggle: (() -> Unit)? = null
    var onCandidateClick: ((index: Int) -> Unit)? = null
    var onCandidateLongClick: ((word: String) -> Unit)? = null
    var onExpandCandidates: (() -> Unit)? = null

    /** Last values pushed by the controller (for trace printing). */
    var lastComposing: String = ""
        private set
    var lastCandidates: List<String> = emptyList()
        private set
    var panelShownWith: List<String>? = null
        private set

    fun setLangIndicator(isChinese: Boolean) {}
    fun setClipPanelMode(showing: Boolean) {}
    fun setClipPanelTitle(total: Int) {}

    fun setComposing(text: String) {
        lastComposing = text
    }

    fun setCandidates(list: List<String>) {
        lastCandidates = list
        panelShownWith = null // mirrors real view: setCandidates dismisses the panel
    }

    fun showCandidatePanel(
        candidates: List<String>,
        composing: String,
        onSelect: (Int) -> Unit,
    ) {
        panelShownWith = candidates
    }

    fun dismissCandidatePanel() {
        panelShownWith = null
    }

    fun isCandidatePanelShowing(): Boolean = panelShownWith != null
}

class SymbolKeyboardView(context: Context = Context()) : View(context) {
    interface Listener {
        fun onSymbolKey(sym: String)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
        fun onBackToLetters()
        fun onGotoDigit()
        fun onGotoSymbol()
        fun onVoiceRecordStart()
        fun onVoiceRecordStop()
    }

    var listener: Listener? = null
    var isChineseMode: Boolean = true
    var enterLabel: String = "⏎"

    fun showSymbol() {}
    fun showDigit() {}
}

class ClipPanelView(context: Context = Context()) : View(context) {
    var featureDisabledHint: Boolean = false
    var onEntryClick: ((ClipStore.ClipEntry) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    fun refresh() {}
}
