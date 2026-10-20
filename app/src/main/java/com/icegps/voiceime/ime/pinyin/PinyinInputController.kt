package com.icegps.voiceime.ime.pinyin

import android.view.inputmethod.EditorInfo
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
 * - 回车：有组合文本时原样上屏拼音字母（不做汉字转换，也不触发编辑器动作）；
 *   无组合文本时，若编辑器声明了动作（搜索/发送等，V81）则 performEditorAction，否则换行
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
    private var symbolKeyboard: SymbolKeyboardView? = null
    private var clipPanel: ClipPanelView? = null

    /** 剪贴板历史变化时，若面板正开着则刷新列表 */
    private val clipStoreListener: () -> Unit = {
        if (clipPanel?.visibility == android.view.View.VISIBLE) {
            clipPanel?.refresh()
            // V78：标题在候选栏，条数变化时同步更新
            bar?.setClipPanelTitle(com.icegps.voiceime.service.clip.ClipStore.list().size)
        }
    }

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
     * V81：当前编辑器声明的回车动作（由 VoiceImeService 在 onStartInput 时下发，
     * 已处理 IME_FLAG_NO_ENTER_ACTION）；[EditorInfo.IME_ACTION_NONE] 表示无动作、回车走换行。
     * 仅在无拼音组合文本时生效——组合态回车永远上屏原始拼音字母。
     */
    var editorAction: Int = EditorInfo.IME_ACTION_NONE

    /**
     * 点候选栏右端 ">" 时触发；为 null 时使用默认的浮层面板实现。
     * 设为非 null 可完全接管展开行为（默认实现不再执行）。
     */
    var onExpandCandidates: (() -> Unit)? = null

    /** 点候选栏左上角 🎤：进入留声输入法设置（由 VoiceImeService 接入） */
    var onOpenSettings: (() -> Unit)? = null

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
            override fun onSymbolPage() = showSymbolKeyboard()
            override fun onDigitPage() = showDigitKeyboard()
            override fun onSpace() = handleSpace()
            override fun onVoiceMode() = switchToVoice()
            override fun onVoiceRecordStart() {
                // 录音前先丢弃未完成的拼音组合，避免转写上屏时残留拼音字母
                discardComposing()
                onVoiceRecordStart?.invoke()
            }
            override fun onVoiceRecordStop() { onVoiceRecordStop?.invoke() }
        }
        bar.onOpenSettings = { onOpenSettings?.invoke() }
        bar.onCandidateClick = { selectCandidate(it) }
        bar.onCandidateLongClick = { word -> onCandidateLongPress(word) }
        bar.onExpandCandidates = { onExpandCandidates?.invoke() ?: expandCandidatePanel() }
        bar.onLangToggle = { toggleLang() } // V70：中/英为候选栏右端独立键，点按切换
        bar.onClipboardClick = { toggleClipPanel() } // V69：点击打开剪贴板历史面板
        com.icegps.voiceime.service.clip.ClipStore.addListener(clipStoreListener)
        refresh()
    }

    /** 绑定剪贴板历史面板（由 VoiceImeService 创建并加入布局） */
    fun attachClipPanel(panel: ClipPanelView) {
        this.clipPanel = panel
        panel.onEntryClick = { entry ->
            // 点选上屏：先把未完成拼音按首选提交，再上屏剪贴板文本
            commitWithComposingFlushed(entry.text)
            hideClipPanel()
        }
        panel.onClose = { hideClipPanel() }
    }

    /** 剪贴板面板是否正开着 */
    fun isClipPanelShowing(): Boolean = clipPanel?.visibility == android.view.View.VISIBLE

    /** 切换剪贴板面板显示/隐藏 */
    private fun toggleClipPanel() {
        if (isClipPanelShowing()) hideClipPanel() else showClipPanel()
    }

    private fun showClipPanel() {
        val panel = clipPanel ?: return
        // 未开启功能时显示提示而非空列表
        panel.featureDisabledHint =
            !com.icegps.voiceime.util.Prefs.isClipEnabled(panel.context)
        panel.refresh()
        keyboard?.visibility = android.view.View.GONE
        symbolKeyboard?.visibility = android.view.View.GONE
        panel.visibility = android.view.View.VISIBLE
        // V78：标题移到候选栏（候选区此时空着，本来就放标题），面板内腾出一行多显示一条
        bar?.setClipPanelTitle(com.icegps.voiceime.service.clip.ClipStore.list().size)
        bar?.setClipPanelMode(true)
    }

    private fun hideClipPanel() {
        clipPanel?.visibility = android.view.View.GONE
        bar?.setClipPanelMode(false) // V78：候选栏恢复正常候选显示
        // 面板只在拼音模式下可开，关闭后回到字母键盘
        if (symbolKeyboard?.visibility != android.view.View.VISIBLE) {
            keyboard?.visibility = android.view.View.VISIBLE
        }
    }

    /** 绑定符号/数字键盘 */
    fun attachSymbol(symbolKeyboard: SymbolKeyboardView) {
        this.symbolKeyboard = symbolKeyboard
        symbolKeyboard.listener = object : SymbolKeyboardView.Listener {
            // 点选后停留（可连打），不自动跳回字母键盘
            override fun onSymbolKey(sym: String) = commitWithComposingFlushed(sym)
            override fun onBackspace() = handleBackspace()
            override fun onEnter() = handleEnter()
            override fun onSpace() = handleSpace()
            override fun onBackToLetters() = showLetterKeyboard()
            override fun onGotoDigit() = showDigitKeyboard()
            override fun onGotoSymbol() = showSymbolKeyboard()
            override fun onVoiceRecordStart() {
                // 符号键盘无拼音组合，直接走录音链路（与拼音键盘同一套）
                discardComposing()
                onVoiceRecordStart?.invoke()
            }
            override fun onVoiceRecordStop() { onVoiceRecordStop?.invoke() }
        }
    }

    /** 切到符号键盘：先把未完成拼音按首选上屏，再隐藏字母键盘/候选栏 */
    fun showSymbolKeyboard() {
        flushComposingAsFirstCandidate()
        keyboard?.visibility = android.view.View.GONE
        bar?.visibility = android.view.View.GONE
        symbolKeyboard?.visibility = android.view.View.VISIBLE
        symbolKeyboard?.showSymbol()
    }

    /** 切到数字键盘 */
    fun showDigitKeyboard() {
        flushComposingAsFirstCandidate()
        keyboard?.visibility = android.view.View.GONE
        bar?.visibility = android.view.View.GONE
        symbolKeyboard?.visibility = android.view.View.VISIBLE
        symbolKeyboard?.showDigit()
    }

    /** 回到字母键盘 */
    fun showLetterKeyboard() {
        symbolKeyboard?.visibility = android.view.View.GONE
        keyboard?.visibility = android.view.View.VISIBLE
        bar?.visibility = android.view.View.VISIBLE
        refresh()
    }

    /** 把未完成拼音按首选上屏（不丢字），供模式切换前调用；非用户主动选择，不计入自学习 */
    private fun flushComposingAsFirstCandidate() {
        val composing = engine.getComposingText()
        if (composing.isNotEmpty()) {
            val first = engine.selectCandidate(0, learn = false) ?: composing
            ic()?.commitText(first, 1)
            engine.clear()
        }
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
        bar?.setLangIndicator(isChinese)
        val conn = ic() ?: return
        if (composing.isNotEmpty()) {
            conn.setComposingText(composing, 1)
        } else {
            conn.finishComposingText()
        }
    }

    private fun handleLetter(ch: Char) {
        if (!isChinese) {
            // 英文模式：直接上屏（含 Shift 大小写），标点保持半角
            ic()?.commitText(ch.toString(), 1)
            return
        }
        // 中文模式：常用标点转全角
        when (ch) {
            ',' -> { commitWithComposingFlushed("，"); return }
            '.' -> { commitWithComposingFlushed("。"); return }
        }
        if (ch in 'A'..'Z') {
            // 中文模式 + 大写锁定：和电脑中文输入法一致，大写字母直接上屏，不走拼音
            commitWithComposingFlushed(ch.toString())
            return
        }
        engine.processKey(ch.lowercaseChar())
        refresh()
    }

    private fun handleSymbol(sym: Char) {
        // 上滑输入的上标符号：中文状态转全角（技术符号保持半角），英文状态保持半角
        if (!isChinese) {
            commitWithComposingFlushed(sym.toString())
            return
        }
        when (sym) {
            '"' -> commitWithComposingFlushed(SymbolWidth.quote(true))
            '\'' -> commitWithComposingFlushed(SymbolWidth.singleQuote(true))
            else -> commitWithComposingFlushed(SymbolWidth.of(sym, true).toString())
        }
    }

    /** 先把未完成的拼音串按首选上屏，再提交给定文本；首选提交非用户主动选择，不计入自学习 */
    private fun commitWithComposingFlushed(text: String) {
        val composing = engine.getComposingText()
        if (composing.isNotEmpty()) {
            val first = engine.selectCandidate(0, learn = false) ?: composing
            ic()?.commitText(first, 1)
        }
        ic()?.commitText(text, 1)
        refresh()
    }

    private fun handleSpace() {
        if (engine.getComposingText().isNotEmpty()) {
            selectCandidate(0) // 标准行为：空格选首选（用户主动，计入自学习）
        } else {
            engine.breakWordChain() // 直接上屏空格，打断造词链
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
        val raw = engine.rawInput()
        if (raw.isNotEmpty()) {
            // 回车直接上屏用户实际键入的原始字母（原封不动）：
            // 双拼模式下不展开成全拼（如 "vk" 不变成 "zhang"），不加 ' 分隔符；
            // 不计入自学习
            ic()?.commitText(raw, 1)
            engine.clear()
            refresh()
        } else {
            engine.breakWordChain() // 直接上屏，打断造词链
            val action = editorAction
            if (action != EditorInfo.IME_ACTION_NONE) {
                // V81：编辑器声明了回车动作（搜索/前往/发送/完成/下一项/上一项）→ 执行动作，不插入换行
                ic()?.performEditorAction(action)
            } else {
                ic()?.commitText("\n", 1)
            }
            refresh()
        }
    }

    /** 用户主动点选候选词（候选栏点按 / 空格选首选 / 浮层点选），计入自学习 */
    private fun selectCandidate(index: Int) {
        val text = engine.selectCandidate(index, learn = true) ?: return
        ic()?.commitText(text, 1)
        refresh()
    }

    /**
     * 候选栏长按候选词：仅个人词典来源的词提供删除。
     * 静态词库的词长按无操作。删除后该词不再参与置顶；
     * 之后再次主动选择可重新学习（v1 无黑名单）。
     */
    private fun onCandidateLongPress(word: String) {
        val adapter = engine as? EngineAdapter ?: return
        if (!adapter.isUserWord(word)) return
        adapter.deleteUserWord(word)
        onToast("已从个人词典删除“$word”")
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
        symbolKeyboard?.isChineseMode = isChinese // V68：符号/数字键盘 。 键跟随中/英
        bar?.setLangIndicator(isChinese) // V67：同步候选栏中/英指示器
        // V70：中/英为独立键点按触发，toast 明确反馈
        onToast(if (isChinese) "已切换为中文输入" else "已切换为英文输入")
        refresh()
    }

    private fun switchToVoice() {
        engine.clear()
        ic()?.finishComposingText()
        refresh()
        onSwitchToVoice()
    }
}
