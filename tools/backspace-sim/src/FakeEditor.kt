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
 * - deleteSurroundingText(b, a): deletes b chars before / a chars after
 *   the cursor in the document.
 *
 * Every call is appended to [log] so the exact IC call sequence the
 * controller produces can be printed per key press.
 */
class FakeEditor : InputConnection {
    val doc = StringBuilder()
    var cursor: Int = 0
        private set
    var compStart: Int = -1
        private set
    var compEnd: Int = -1
        private set
    val log = mutableListOf<String>()

    val hasComposing: Boolean get() = compStart >= 0

    fun docWithMarkers(): String {
        if (!hasComposing) return "\"$doc\" (no composing region)"
        val sb = StringBuilder(doc)
        sb.insert(compEnd, "⟧").insert(compStart, "⟦")
        return "\"$sb\" composing=[$compStart,$compEnd) cursor=$cursor"
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
            from = cursor; to = cursor
        }
        replaceRange(from, to, text)
        compStart = from
        compEnd = from + text.length
        cursor = placeCursor(newCursorPosition)
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        log.add("commitText(\"$text\", $newCursorPosition)")
        val from: Int
        val to: Int
        if (hasComposing) {
            from = compStart; to = compEnd
        } else {
            from = cursor; to = cursor
        }
        replaceRange(from, to, text)
        compStart = -1; compEnd = -1
        cursor = from + text.length + (newCursorPosition - 1)
        return true
    }

    override fun finishComposingText(): Boolean {
        log.add("finishComposingText()  // markers dropped, TEXT KEPT (AOSP semantics)")
        compStart = -1; compEnd = -1
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        log.add("deleteSurroundingText($beforeLength, $afterLength)")
        val from = maxOf(0, cursor - beforeLength)
        val to = minOf(doc.length, cursor + afterLength)
        if (to > from) {
            replaceRange(from, to, "")
            cursor = from
        }
        // Reaching states in these scenarios never carry a composing
        // region when this is called (controller only calls it when the
        // engine composing is empty), so no span adjustment is needed.
        compStart = -1; compEnd = -1
        return true
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
