import android.view.inputmethod.InputConnection

/**
 * HARNESS-ONLY fake editor (not part of the app).
 *
 * Implements InputConnection with the DOCUMENTED Android semantics that
 * matter for this investigation (mirroring AOSP BaseInputConnection /
 * EditableInputConnection, which real EditText-based editors use):
 *
 * - setComposingText(t, 1): replaces the current composing region (or
 *   inserts at the cursor when none) and marks the inserted text as the
 *   new composing region.
 * - commitText(t, 1): same replacement, but the inserted text is NOT
 *   composing afterwards (any composing region ends).
 * - finishComposingText(): only removes the "composing" marking.
 *   *** THE TEXT ITSELF STAYS IN THE DOCUMENT ***
 *   (AOSP implementation calls removeComposingSpans(); the characters
 *   are left untouched. To DELETE composing text an IME must first
 *   setComposingText("", 1) — exactly what the controller's own
 *   discardComposing() does.)
 * - deleteSurroundingText(b, a): deletes b chars before the START of the
 *   current selection and a chars after its END (AOSP
 *   BaseInputConnection.deleteSurroundingText operates on the selection
 *   endpoints). *** THE SELECTED TEXT ITSELF IS NEVER DELETED *** and the
 *   selection stays in place. With a collapsed selection (plain cursor)
 *   this degenerates to "b before / a after the cursor".
 * - getSelectedText(0): the selected substring, or null when the
 *   selection is collapsed (AOSP returns null for an empty selection).
 * - commitText / setComposingText with no composing region REPLACE the
 *   current selection (AOSP semantics) — which is why commitText("", 1)
 *   is the standard way to delete a selection.
 *
 * Every call is appended to [log] so the exact IC call sequence the
 * controller produces can be printed per key press.
 */
class FakeEditor : InputConnection {
    val doc = StringBuilder()
    var cursor: Int = 0
        private set
    var selStart: Int = 0
        private set
    var selEnd: Int = 0
        private set
    var compStart: Int = -1
        private set
    var compEnd: Int = -1
        private set
    val log = mutableListOf<String>()

    val hasComposing: Boolean get() = compStart >= 0
    val hasSelection: Boolean get() = selEnd > selStart

    /** Scenario setup: seed the document and place a selection (user long-press selection). */
    fun seed(text: String, selFrom: Int, selTo: Int) {
        doc.setLength(0); doc.append(text)
        compStart = -1; compEnd = -1
        selStart = selFrom; selEnd = selTo
        cursor = selTo
        log.clear()
    }

    private fun collapseSelection(to: Int) {
        selStart = to; selEnd = to; cursor = to
    }

    fun docWithMarkers(): String {
        val sb = StringBuilder(doc)
        if (hasComposing) {
            sb.insert(compEnd, "⟧").insert(compStart, "⟦")
            return "\"$sb\" composing=[$compStart,$compEnd) cursor=$cursor"
        }
        if (hasSelection) {
            sb.insert(selEnd, "】").insert(selStart, "【")
            return "\"$sb\" selection=[$selStart,$selEnd) cursor=$cursor (no composing region)"
        }
        return "\"$doc\" cursor=$cursor (no composing region)"
    }

    private fun replaceRange(from: Int, to: Int, text: CharSequence) {
        doc.replace(from, to, text.toString())
    }

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        log.add("setComposingText(\"$text\", $newCursorPosition)")
        val from: Int
        val to: Int
        if (hasComposing) {
            from = compStart; to = compEnd
        } else {
            // AOSP: with no composing region, the current SELECTION is replaced.
            from = selStart; to = selEnd
        }
        replaceRange(from, to, text)
        compStart = from
        compEnd = from + text.length
        cursor = placeCursor(newCursorPosition)
        collapseSelection(cursor)
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        log.add("commitText(\"$text\", $newCursorPosition)")
        val from: Int
        val to: Int
        if (hasComposing) {
            from = compStart; to = compEnd
        } else {
            // AOSP: with no composing region, the current SELECTION is replaced
            // (this is what makes commitText("", 1) delete a selection).
            from = selStart; to = selEnd
        }
        replaceRange(from, to, text)
        compStart = -1; compEnd = -1
        cursor = from + text.length + (newCursorPosition - 1)
        collapseSelection(cursor)
        return true
    }

    override fun finishComposingText(): Boolean {
        log.add("finishComposingText()  // markers dropped, TEXT KEPT (AOSP semantics)")
        compStart = -1; compEnd = -1
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        log.add("deleteSurroundingText($beforeLength, $afterLength)")
        // AOSP BaseInputConnection semantics: deletion is anchored at the
        // SELECTION endpoints — up to [beforeLength] chars immediately
        // before selStart and [afterLength] chars immediately after selEnd.
        // The selected text itself is NOT touched and the selection remains
        // selected (its start shifts left by what was deleted before it).
        val delBeforeFrom = maxOf(0, selStart - beforeLength)
        val delAfterTo = minOf(doc.length, selEnd + afterLength)
        val kept = StringBuilder()
            .append(doc.substring(0, delBeforeFrom))
            .append(doc.substring(selStart, selEnd))
            .append(doc.substring(delAfterTo))
        val removedBefore = selStart - delBeforeFrom
        doc.setLength(0); doc.append(kept)
        selStart -= removedBefore
        selEnd -= removedBefore
        cursor = selEnd
        // Reaching states in these scenarios never carry a composing
        // region when this is called (controller only calls it when the
        // engine composing is empty), so no span adjustment is needed.
        compStart = -1; compEnd = -1
        return true
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        log.add("getSelectedText($flags)")
        return if (hasSelection) doc.substring(selStart, selEnd) else null
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        log.add("performEditorAction($actionCode)")
        return true
    }

    private fun placeCursor(ncp: Int): Int = when {
        ncp > 0 -> compEnd + ncp - 1
        ncp < 0 -> compStart + ncp + 1
        else -> compEnd
    }.coerceIn(0, doc.length)
}
