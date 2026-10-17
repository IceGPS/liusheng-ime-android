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
 * - 回车：有组合文本时原样上屏拼音字母（不做汉字转换）；无组合文本时 commitText("\n")
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

    /**
     * 点候选栏右端 ">" 时触发；为 null 时使用默认的浮层面板实现。
     * 设为非 null 可完全接管展开行为（默认实现不再执行）。
     */
    var onExpandCandidates: (() -> Unit)? = null

    /** 空格麦克风按住开始录音（由 VoiceImeService 接入 startRecording） */
    var onVoiceRecordStart: (() -> Unit)? = null

    /** 空格麦克风松开结束录音（由 VoiceImeService 接入 stopAndTranscribe） */
    var onVoiceRecordStop: (() -> Unit)? = null

    /** 全部候选词（不截断，浮层面板用） */
    fun getAllCandidates(): List<String> = engine.getAllCandidates()

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
            override fun onVoiceRecordStart() {
                // 录音前先丢弃未完成的拼音组合，避免转写上屏时残留拼音字母
                discardComposing()
                onVoiceRecordStart?.invoke()
            }
            override fun onVoiceRecordStop() { onVoiceRecordStop?.invoke() }
        }
        bar.onToggleVoice = { switchToVoice() }
        bar.onCandidateClick = { selectCandidate(it) }
        bar.onExpandCandidates = { onExpandCandidates?.invoke() ?: expandCandidatePanel() }
        refresh()
    }

    /**
     * 从 Prefs 同步拼音方案（全拼/小鹤）到引擎。
     * 在键盘显示时调用，确保设置页的修改即时生效。
     */
    fun syncScheme(ctx: android.content.Context) {
        val mode = when (com.icegps.voiceime.util.Prefs.getPinyinScheme(ctx)) {
            "xiaohe" -> PinyinMode.XIAOHE_SHUANGPIN
            else -> PinyinMode.QUANPIN
        }
        engine.setMode(mode)
        engine.clear()
        refresh()
    }

    /** 重置输入状态（输入结束 / 切换模式 / 切换中英文时调用） */
    fun reset() {
        engine.clear()
        refresh()
    }

    /**
     * 丢弃未完成的拼音组合，并清除编辑器中的组合文本（不留拼音字母）。
     * 语音录音开始前调用，避免转写上屏时与残留拼音混在一起。
     */
    fun discardComposing() {
        engine.clear()
        ic()?.let {
            it.setComposingText("", 1)
            it.finishComposingText()
        }
        refresh()
    }

    private fun ic() = connectionProvider()

    /**
     * 刷新三处显示：候选栏组合区、候选栏候选词、InputConnection 的 composing。
     * 候选栏显示全部候选词（横向滑动浏览）；组合文本为空时清掉下划线。
     */
    private fun refresh() {
        val composing = engine.getComposingText()
        bar?.setComposing(composing)
        bar?.setCandidates(engine.getAllCandidates())
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
        val composing = engine.getComposingText()
        if (composing.isNotEmpty()) {
            // 回车直接上屏原始拼音字母（原封不动），不做拼音到汉字的转换
            ic()?.commitText(composing, 1)
            engine.clear()
            refresh()
        } else {
            ic()?.commitText("\n", 1)
            refresh()
        }
    }

    private fun selectCandidate(index: Int) {
        val text = engine.selectCandidate(index) ?: return
        ic()?.commitText(text, 1)
        refresh()
    }

    /**
     * 默认的浮层展开实现：弹出候选面板显示全部候选词。
     * 面板中的点击按绝对下标选中（selectCandidate 已兼容全量下标）。
     * 选中上屏后 refresh() 会重建候选栏并自动关闭浮层。
     */
    private fun expandCandidatePanel() {
        val bar = this.bar ?: return
        if (engine.getComposingText().isEmpty()) return
        bar.showCandidatePanel(
            candidates = engine.getAllCandidates(),
            composing = engine.getComposingText(),
        ) { index -> selectCandidate(index) }
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
