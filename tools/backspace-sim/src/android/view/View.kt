package android.view

import android.content.Context

/**
 * HARNESS-ONLY stub (not part of the app).
 * Minimal View surface used by the verbatim PinyinInputController:
 * visibility constants + a mutable visibility + a context.
 */
open class View(val context: Context = Context()) {
    var visibility: Int = VISIBLE

    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
    }
}
