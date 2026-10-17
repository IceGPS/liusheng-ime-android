package com.icegps.voiceime.ime.pinyin

import android.view.inputmethod.InputConnection

/**
 * 拼音输入控制器：把键盘 / 候选栏的 UI 事件翻译成 InputConnection 操作。
 *
 * 拥有 [PinyinEngine]（第一阶段为 [StubPinyinEngine]，第二阶段替换为 RIME JNI 实现）。
 *
 * 与 InputConnection 的对接规则：
 * - 拼音组合文本用 setComposingText 显示（下划线预编辑区）
 * - 选中候选词用 commitText 上屏
 * - 退格：有组合文本时先删拼音，无组合文本时 deleteSurroundingText
 * - 回车：有候选时上屏首选；无候选时 commitText("\n")
 * - 空格：有组合文本时选首选；无组合文本时输入空格
 */
class PinyinInputController(
    private val connectionProvider: () -> InputConnection?,
    private val onSwitchToVoice: () -> Unit,
    private val onToast: (String) -> Unit = {},
) {
    private var engine: PinyinEngine = StubPinyinEngine()
    private var keyboard: PinyinKeyboardView? = null
    private var bar: CandidateBarView? = null

    /**
     * 替换拼音引擎（第二阶段：换成 RIME JNI / 纯 Kotlin 真实引擎实现）。
     * 替换后清空当前输入状态并刷新显示。
     */
    fun setEngine(newEngine: PinyinEngine) {
        engine = newEngine
        reset()
    }

    /** 中文 / 英文模式（对应键盘“中/英”键），默认中文 */
    var isChinese = true
        private set

    /** 绑定键盘与候选栏，设置事件回调 */
    fun attach(keyboard: PinyinKeyboardView, bar: CandidateBarView) {
        this.keyboard = keyboard
        this.bar = bar
        keyboard.listener = object : PinyinKeyboardView.Listener {
            override fun onLetter(ch: Char) = handleLetter(ch)
            override fun onSymbol(sym: Char) = handleSymbol(sym)
            override fun onBackspace() = handleBackspace()
            override fun onEnter() = handleEnter()
            override fun onShift() {
                // Shift 视觉状态由键盘内部维护，这里无需额外动作
            }
            override fun onLangToggle() = toggleLang()
            override fun onSymbolPage() = onToast("符号键盘第二阶段再做")
            override fun onDigitPage() = onToast("数字键盘第二阶段再做")
            override fun onSpace() = handleSpace()
            override fun onVoiceMode() = switchToVoice()
        }
        bar.onToggleVoice = { switchToVoice() }
        bar.onCandidateClick = { selectCandidate(it) }
        engine.setMode(PinyinMode.QUANPIN)
        refresh()
    }

    /** 重置输入状态（输入结束 / 切换模式 / 切换中英文时调用） */
    fun reset() {
        engine.clear()
        refresh()
    }

    private fun ic() = connectionProvider()

    /**
     * 刷新三处显示：候选栏组合区、候选栏候选词、InputConnection 的 composing。
     * 组合文本为空时调 finishComposing() 清掉下划线。
     */
    private fun refresh() {
        val composing = engine.getComposingText()
        bar?.setComposing(composing)
        bar?.setCandidates(engine.getCandidates())
        val conn = ic() ?: return
        if (composing.isNotEmpty()) {
            conn.setComposingText(composing, 1)
        } else {
            conn.finishComposingText()
        }
    }

    private fun handleLetter(ch: Char) {
        if (!isChinese) {
            // 英文模式：直接上屏（含 Shift 大小写）
            ic()?.commitText(ch.toString(), 1)
            return
        }
        // 中文模式：逗号键出中文逗号
        if (ch == ',') {
            commitWithComposingFlushed("，")
            return
        }
        engine.processKey(ch.lowercaseChar())
        refresh()
    }

    private fun handleSymbol(sym: Char) {
        commitWithComposingFlushed(sym.toString())
    }

    /** 先把未完成的拼音串按首选上屏，再提交给定文本 */
    private fun commitWithComposingFlushed(text: String) {
        val composing = engine.getComposingText()
        if (composing.isNotEmpty()) {
            val first = engine.selectCandidate(0) ?: composing
            ic()?.commitText(first, 1)
        }
        ic()?.commitText(text, 1)
        refresh()
    }

    private fun handleSpace() {
        if (engine.getComposingText().isNotEmpty()) {
            selectCandidate(0) // 标准行为：空格选首选
        } else {
            ic()?.commitText(" ", 1)
        }
    }

    private fun handleBackspace() {
        if (engine.getComposingText().isNotEmpty()) {
            engine.deleteLast()
            refresh()
        } else {
            ic()?.deleteSurroundingText(1, 0)
        }
    }

    private fun handleEnter() {
        if (engine.getCandidates().isNotEmpty()) {
            // 有候选时只上屏首选，不换行
            selectCandidate(0)
        } else {
            engine.clear()
            ic()?.commitText("\n", 1)
            refresh()
        }
    }

    private fun selectCandidate(index: Int) {
        val text = engine.selectCandidate(index) ?: return
        ic()?.commitText(text, 1)
        refresh()
    }

    private fun toggleLang() {
        isChinese = !isChinese
        engine.clear()
        keyboard?.isChineseMode = isChinese
        refresh()
    }

    private fun switchToVoice() {
        engine.clear()
        ic()?.finishComposingText()
        refresh()
        onSwitchToVoice()
    }
}
