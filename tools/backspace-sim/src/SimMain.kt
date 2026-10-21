import android.content.Context
import com.icegps.voiceime.ime.pinyin.CandidateBarView
import com.icegps.voiceime.ime.pinyin.EngineAdapter
import com.icegps.voiceime.ime.pinyin.PinyinInputController
import com.icegps.voiceime.ime.pinyin.PinyinKeyboardView
import com.icegps.voiceime.ime.pinyin.SymbolKeyboardView
import com.icegps.voiceime.util.Prefs

/**
 * Controller-level simulation of the "empty backspace" report.
 *
 * Drives the VERBATIM PinyinInputController + EngineAdapter + real engine
 * against a FakeEditor implementing documented Android InputConnection
 * semantics, through the same Listener callbacks the on-screen keyboards
 * use. Prints, per backspace press: engine raw buffer, candidate count,
 * the IC calls emitted, and the editor document state.
 */

private class Rig(scheme: String) {
    val ctx = Context()
    val editor = FakeEditor()
    val kb = PinyinKeyboardView(ctx)
    val bar = CandidateBarView(ctx)
    val symKb = SymbolKeyboardView(ctx)
    val adapter = EngineAdapter(ctx)
    val controller = PinyinInputController({ editor }, {}, {})

    init {
        Prefs.scheme = scheme
        controller.setEngine(adapter)
        controller.attach(kb, bar)
        controller.attachSymbol(symKb)
        controller.syncScheme(ctx)
        editor.log.clear()
    }

    fun type(s: String) = s.forEach { kb.listener!!.onLetter(it) }

    fun state(tag: String) {
        val drained = editor.log.toList(); editor.log.clear()
        println(
            "  [$tag] raw=\"${adapter.rawInput()}\" engineComposing=\"${adapter.getComposingText()}\"" +
                " barCands=${bar.lastCandidates.size} editor=${editor.docWithMarkers()}" +
                if (drained.isEmpty()) "" else "  IC: ${drained.joinToString(" | ")}"
        )
    }
}

/** Press backspace until engine buffer and editor doc are both empty. Returns press count. */
private fun backspaceToEmpty(rig: Rig, label: String): Int {
    var presses = 0
    var prevDocLen = rig.editor.doc.length
    while (presses < 10) {
        if (rig.adapter.rawInput().isEmpty() && rig.editor.doc.isEmpty()) break
        rig.kb.listener!!.onBackspace()
        presses++
        val docLen = rig.editor.doc.length
        val mark = if (docLen >= prevDocLen) "  <== EMPTY PRESS (no document char deleted)" else ""
        rig.state("$label ⌫#$presses$mark")
        prevDocLen = docLen
    }
    return presses
}

fun main() {
    println("=== Scenario A: XIAOHE, type hjzi (汉字, 2 syllables / 4 keys), backspace to empty ===")
    Rig("xiaohe").also { rig ->
        rig.type("hjzi")
        rig.state("typed")
        val n = backspaceToEmpty(rig, "A")
        println("  => total backspace presses to fully clear 4 typed keys: $n (expected 4)")
    }

    println()
    println("=== Scenario B: QUANPIN, type hanzi (5 keys), backspace to empty ===")
    Rig("quanpin").also { rig ->
        rig.type("hanzi")
        rig.state("typed")
        val n = backspaceToEmpty(rig, "B")
        println("  => total backspace presses: $n (expected 5)")
    }

    println()
    println("=== Scenario C: XIAOHE hjzi, delete engine buffer to empty, then type 'ni' ===")
    Rig("xiaohe").also { rig ->
        rig.type("hjzi")
        repeat(4) { rig.kb.listener!!.onBackspace() }
        rig.state("after 4x⌫ (engine buffer empty)")
        rig.type("ni")
        rig.state("typed ni")
        println("  => if the editor still holds a committed stray char before the new composing, the leak is real text, not a redraw artifact")
    }

    println()
    println("=== Scenario D: QUANPIN nihao -> switch to symbol keyboard (flush) -> symbol ⌫ ===")
    Rig("quanpin").also { rig ->
        rig.type("nihao")
        rig.kb.listener!!.onSymbolPage() // flush composing as first candidate, engine cleared
        rig.state("after switch to symbol keyboard")
        repeat(2) { i ->
            rig.symKb.listener!!.onBackspace()
            rig.state("symbol ⌫#${i + 1}")
        }
        println("  => each symbol-keyboard press must delete exactly one committed char")
    }

    println()
    println("=== Scenario F: QUANPIN nihao -> toggle 中/英 mid-composing ===")
    Rig("quanpin").also { rig ->
        rig.type("nihao")
        rig.bar.onLangToggle!!.invoke() // controller.toggleLang(): engine.clear() + refresh()
        rig.state("after toggleLang")
        println("  => editor doc must be empty: discarding composing must not commit the pinyin display text")
    }

    println()
    println("=== Scenario G: QUANPIN nihao -> switch to voice mid-composing ===")
    Rig("quanpin").also { rig ->
        rig.type("nihao")
        rig.kb.listener!!.onVoiceMode() // controller.switchToVoice(): engine.clear() + finish + refresh()
        rig.state("after switchToVoice")
        println("  => editor doc must be empty: switching mode must not commit the pinyin display text")
    }

    println()
    println("=== Scenario E: QUANPIN nihao -> space selects candidate (assoc engages) -> ⌫ ===")
    Rig("quanpin").also { rig ->
        rig.type("nihao")
        rig.kb.listener!!.onSpace() // selectCandidate(0); association list from stub engine
        rig.state("after select (assoc words in bar: ${rig.bar.lastCandidates})")
        repeat(2) { i ->
            rig.kb.listener!!.onBackspace()
            rig.state("⌫#${i + 1}")
        }
        println("  => leaving association mode must not eat a press: each ⌫ deletes one committed char")
    }
}
