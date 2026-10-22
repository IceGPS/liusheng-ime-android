package android.view.inputmethod

/**
 * HARNESS-ONLY stub (not part of the app).
 * The subset of InputConnection that the verbatim PinyinInputController calls.
 */
interface InputConnection {
    fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean
    fun finishComposingText(): Boolean
    fun commitText(text: CharSequence, newCursorPosition: Int): Boolean
    fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean
    fun getSelectedText(flags: Int): CharSequence?
    fun performEditorAction(actionCode: Int): Boolean
}
