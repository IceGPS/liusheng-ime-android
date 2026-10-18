package com.icegps.voiceime.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast
import com.icegps.voiceime.ime.pinyin.CandidateBarView
import com.icegps.voiceime.ime.pinyin.PinyinInputController
import com.icegps.voiceime.ime.pinyin.EngineAdapter
import com.icegps.voiceime.ime.pinyin.PinyinKeyboardView
import com.icegps.voiceime.service.Recorder
import com.icegps.voiceime.service.TranscriptLogger
import com.icegps.voiceime.service.asr.AsrBackend
import com.icegps.voiceime.service.asr.LanAsrBackend
import com.icegps.voiceime.service.asr.SherpaAsrBackend
import com.icegps.voiceime.util.Prefs
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 极简语音输入法。
 *
 * 界面：
 *  - 🎤 语音键（兼空格键）：短按输入空格，长按语音输入（不限按压位置）；
 *    松开转写上屏（走系统 commitText，光标天然正确）
 *  - ⌫ 点按删一个字符，长按连续删除
 *  - 🌐 切回上一个输入法
 *  - ⇧ 展开/收起符号区和数字区（均可横滑）
 *
 * 转写复用现有后端（局域网 / 端侧 MNN），与悬浮球同一套逻辑。
 */
class VoiceImeService : InputMethodService() {

    private val uiHandler = Handler(Looper.getMainLooper())
    private val recorder = Recorder()
    private var sherpaBackend: SherpaAsrBackend? = null

    // 长按退格连续删除
    private val backspaceHandler = Handler(Looper.getMainLooper())
    private var backspaceRepeating = false
    private val backspaceRepeat = object : Runnable {
        override fun run() {
            if (!backspaceRepeating) return
            currentInputConnection?.deleteSurroundingText(1, 0)
            backspaceHandler.postDelayed(this, 50)
        }
    }

    override fun onCreateInputView(): View {
        val ctx = this
        // 跟随系统深浅色
        val isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isDark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
        val btnColor = if (isDark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

        // 外层垂直布局：候选栏（拼音模式）+ 语音模式三行 / 拼音键盘
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(bgColor)
        }

        // 候选栏：拼音模式下显示（拼音小耳朵 + 候选词横滚）。
        // 高度固定 60dp：输入法窗口高度恒定，目标 App 编辑区不随输入状态伸缩闪烁。
        val candidateBar = CandidateBarView(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(60),
            )
        }
        this.candidateBar = candidateBar

        // 语音模式容器：装原来的符号行、数字行、主输入行
        val voiceBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        this.voiceBox = voiceBox

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 数字/标点区开关：最左边，空心上箭头
        val numpadToggleBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = if (Prefs.isNumPadExpanded(ctx)) "⇩" else "⇧"
            textSize = 20f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(2)
            }
        }

        // 语音键（兼空格键）：短按输入空格，长按语音输入（不限按压位置）
        val voiceBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "🎤"
            textSize = 20f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 2f).apply {
                marginEnd = dp(2)
            }
        }
        this.voiceBtn = voiceBtn
        // 保存原始背景色，用于录音结束恢复
        voiceBtnOrigBgColor = btnColor
        // 语音键（兼空格键）触摸状态机（V48）：
        // - 非 VAD 模式：短按（不限按压位置）输入空格；长按（不限按压位置）语音输入
        // - VAD 模式：保持原有中央 mic 热区逻辑不动
        var voiceRecording = false   // 当前这次触摸是否在录音
        var voiceTapValid = false    // 当前这次触摸是否为有效短按（输入空格）
        var voiceDownX = 0f
        var voiceDownY = 0f
        // 长按判定：100ms（用户要求，500ms 体感太长）
        val longPressTimeout = 100L
        val longPressRunnable = Runnable {
            // 长按触发：手指未松开且未滑出时转入语音录音
            if (voiceTapValid && !voiceRecording) {
                voiceTapValid = false
                if (startRecording()) {
                    voiceRecording = true
                }
            }
        }
        voiceBtn.setOnTouchListener { v, event ->
            if (Prefs.isVadEnabled(this)) {
                // ===== VAD 模式：原有中央 mic 热区逻辑，不动 =====
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        val halfHot = dp(28).toFloat() // 56dp 热区的一半
                        if (abs(event.x - v.width / 2f) <= halfHot) {
                            if (isVadRecording) {
                                stopVadRecording()
                            } else {
                                startVadRecording()
                            }
                            voiceTapValid = false
                        } else {
                            voiceRecording = false
                            voiceTapValid = true
                            voiceDownX = event.x
                            v.alpha = 0.5f
                        }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!voiceRecording && voiceTapValid &&
                            abs(event.x - voiceDownX) > dp(10)) {
                            voiceTapValid = false
                            v.alpha = 1f
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (voiceRecording) {
                            voiceRecording = false
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            stopAndTranscribe()
                        } else {
                            v.alpha = 1f
                            if (voiceTapValid) {
                                currentInputConnection?.commitText(" ", 1)
                            }
                            voiceTapValid = false
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (voiceRecording) {
                            voiceRecording = false
                            stopAndTranscribe()
                        } else {
                            v.alpha = 1f
                            voiceTapValid = false
                        }
                        true
                    }
                    else -> false
                }
            } else {
                // ===== 非 VAD 模式：短按空格 / 长按语音（不限按压位置）=====
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        voiceRecording = false
                        voiceTapValid = true
                        voiceDownX = event.x
                        voiceDownY = event.y
                        v.alpha = 0.5f
                        v.postDelayed(longPressRunnable, longPressTimeout)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 未进入录音前滑出 10dp：取消短按和长按，避免误触；
                        // 已在录音则手指滑动不影响
                        if (!voiceRecording && voiceTapValid &&
                            (abs(event.x - voiceDownX) > dp(10) ||
                             abs(event.y - voiceDownY) > dp(10))) {
                            voiceTapValid = false
                            v.removeCallbacks(longPressRunnable)
                            v.alpha = 1f
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.removeCallbacks(longPressRunnable)
                        v.alpha = 1f
                        if (voiceRecording) {
                            // 长按录音：松开停止转写（stopAndTranscribe 内部恢复背景）
                            voiceRecording = false
                            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            stopAndTranscribe()
                        } else if (voiceTapValid) {
                            // 有效短按：输入空格
                            voiceTapValid = false
                            currentInputConnection?.commitText(" ", 1)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // 手势被系统中断：停止录音或取消短按
                        v.removeCallbacks(longPressRunnable)
                        v.alpha = 1f
                        if (voiceRecording) {
                            voiceRecording = false
                            stopAndTranscribe()
                        }
                        voiceTapValid = false
                        true
                    }
                    else -> false
                }
            }
        }

        // 退格键：点按删一个，长按连续删
        val backBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "⌫"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(2)
            }
        }
        backBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    currentInputConnection?.deleteSurroundingText(1, 0)
                    backspaceRepeating = true
                    backspaceHandler.postDelayed(backspaceRepeat, 400)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    backspaceRepeating = false
                    backspaceHandler.removeCallbacks(backspaceRepeat)
                    true
                }
                else -> false
            }
        }

        // 设置键：打开设置页（放最左：切换服务端/端侧模型）
        val settingsBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "⚙️"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(2)
            }
        }
        settingsBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    v.performClick()
                    val intent = android.content.Intent(ctx, com.icegps.voiceime.ui.SettingsActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        // 切回上一个输入法
        val switchBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "🌐"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(2)
            }
        }
        switchBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    v.performClick()
                    switchToPreviousIme()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        // 回车键：换行（放最右）
        val enterBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "⏎"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f)
        }
        enterBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    currentInputConnection?.commitText("\n", 1)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        // 拼音键盘切换键：切到 QWERTY 拼音键盘
        val pinyinBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "⌨️"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(2)
            }
        }
        pinyinBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    v.performClick()
                    showPinyinKeyboard()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        row.addView(numpadToggleBtn)
        row.addView(settingsBtn)
        row.addView(switchBtn)
        row.addView(pinyinBtn)
        row.addView(voiceBtn)
        row.addView(backBtn)
        row.addView(enterBtn)

        // 符号/数字区：HorizontalScrollView 包裹 LinearLayout，支持左右滑动
        // 两行默认折叠，点 ⇧ 展开/收起，状态持久化
        val symbolScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            visibility = if (Prefs.isNumPadExpanded(ctx)) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(4))
        }
        // 第一行：常用符号（加减乘除等号百分号已挪到数字区）
        val symbolRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        symbolScroll.addView(
            symbolRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        // 第二行：数字 + 数学符号
        val digitScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            visibility = if (Prefs.isNumPadExpanded(ctx)) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(4))
        }
        val digitRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        digitScroll.addView(
            digitRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        fun makeCharBtn(ch: String): Button {
            val b = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            styleBtn(this, btnColor, textColor)
                text = ch
                textSize = 18f
                isHapticFeedbackEnabled = true
                // 滑动行内用固定宽度（weight 在 ScrollView 里不生效）
                layoutParams = LinearLayout.LayoutParams(dp(52), dp(48)).apply {
                    marginEnd = dp(1)
                }
            }
            b.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.5f
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        currentInputConnection?.commitText(ch, 1)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1f
                        true
                    }
                    else -> false
                }
            }
            return b
        }
        // 第一行符号：中英文常用标点（点按上屏后不自动收起，方便连续输入）
        for (ch in listOf("，","。","？","_","$","@","#","'","\"",";","!",":","、","…","—","·","～")) {
            symbolRow.addView(makeCharBtn(ch))
        }
        // 第二行：数字 1-0 + 数学符号（与拼音键盘上标数字顺序一致）
        for (ch in listOf("1","2","3","4","5","6","7","8","9","0","+","-","×","÷","=","%",">","<","(",")",".",",")) {
            digitRow.addView(makeCharBtn(ch))
        }

        // 开关点击：展开/折叠
        numpadToggleBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    val expanded = symbolScroll.visibility == View.VISIBLE
                    if (expanded) {
                        symbolScroll.visibility = View.GONE
                        digitScroll.visibility = View.GONE
                        (v as Button).text = "⇧"
                        Prefs.setNumPadExpanded(ctx, false)
                    } else {
                        symbolScroll.visibility = View.VISIBLE
                        digitScroll.visibility = View.VISIBLE
                        (v as Button).text = "⇩"
                        Prefs.setNumPadExpanded(ctx, true)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        // 语音模式各行装进 voiceBox：符号区、数字区、主行
        voiceBox.addView(symbolScroll)
        voiceBox.addView(digitScroll)
        voiceBox.addView(row)

        // 拼音键盘（默认隐藏）
        val pinyinKeyboard = PinyinKeyboardView(ctx).apply {
            visibility = View.GONE
        }
        this.pinyinKeyboard = pinyinKeyboard

        // 拼音输入控制器：键盘 / 候选栏事件 → InputConnection
        pinyinController = PinyinInputController(
            connectionProvider = { currentInputConnection },
            onSwitchToVoice = { showVoiceKeyboard() },
            onToast = { msg -> toast(msg) },
        ).apply {
            attach(pinyinKeyboard, candidateBar)
            // 接入真实引擎（词典 + 小鹤双拼，替代桩实现）
            setEngine(EngineAdapter(ctx))
            // 拼音键盘空格麦克风：按住录音、松开转写（与极简语音键盘同一套录音链路）
            onVoiceRecordStart = { startRecording() }
            onVoiceRecordStop = { stopAndTranscribe() }
        }

        // 顺序：候选栏、语音模式、拼音键盘
        root.addView(candidateBar)
        root.addView(voiceBox)
        root.addView(pinyinKeyboard)

        // 恢复上次的键盘模式
        if (Prefs.getKbMode(ctx) == Prefs.KB_PINYIN) {
            showPinyinKeyboard()
        } else {
            showVoiceKeyboard()
        }

        return root
    }

    // ---------- 拼音键盘（V32）：模式切换 ----------

    private var candidateBar: CandidateBarView? = null
    private var voiceBox: LinearLayout? = null
    private var pinyinKeyboard: PinyinKeyboardView? = null
    private var pinyinController: PinyinInputController? = null

    /** 切换到拼音键盘（候选栏 + QWERTY），状态持久化 */
    private fun showPinyinKeyboard() {
        voiceBox?.visibility = View.GONE
        candidateBar?.visibility = View.VISIBLE
        pinyinKeyboard?.visibility = View.VISIBLE
        Prefs.setKbMode(this, Prefs.KB_PINYIN)
        pinyinController?.syncScheme(this)
    }

    /** 切回语音键行，状态持久化 */
    private fun showVoiceKeyboard() {
        currentInputConnection?.finishComposingText()
        pinyinController?.reset()
        pinyinKeyboard?.visibility = View.GONE
        candidateBar?.visibility = View.GONE
        voiceBox?.visibility = View.VISIBLE
        Prefs.setKbMode(this, Prefs.KB_VOICE)
    }

    override fun onFinishInput() {
        // 输入结束时清掉拼音组合状态，避免残留到下一个输入框
        pinyinController?.reset()
        super.onFinishInput()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 根据深浅色给按钮上样式 */
    private fun styleBtn(b: Button, btnColor: Int, textColor: Int) {
        b.setBackgroundColor(btnColor)
        b.setTextColor(textColor)
    }

    private var voiceBtn: Button? = null

    /** 语音按钮原始背景色（录音时变红提示，结束后恢复） */
    private var voiceBtnOrigBgColor: Int = 0

    private var recordStartMs: Long = 0
    private var isVadRecording = false
    private var vadMonitorThread: Thread? = null
    @Volatile private var vadStopRequested = false

    private fun startVadRecording() {
        if (!recorder.start()) {
            toast("录音启动失败，请检查麦克风权限")
            return
        }
        isVadRecording = true
        recordStartMs = System.currentTimeMillis()
        vadStopRequested = false
        // 红底提示 VAD 录音中（按钮文字保持 🎤）
        uiHandler.post { voiceBtn?.setBackgroundColor(0xFFE53935.toInt()) }

        // VAD 监控线程：检测静音自动停止
        val silenceMs = Prefs.getVadSilenceMs(this)
        vadMonitorThread = thread {
            var silenceStart = -1L
            val rmsThreshold = 0.02f // RMS 低于此值视为静音
            while (!vadStopRequested && isVadRecording) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                }
                val rms = recorder.getCurrentRms()
                if (rms < 0) continue
                val now = System.currentTimeMillis()
                // 至少录 1 秒后才开始检测静音（避免刚开始就停）
                if (now - recordStartMs < 1000) continue
                if (rms < rmsThreshold) {
                    if (silenceStart < 0) silenceStart = now
                    if (now - silenceStart >= silenceMs) {
                        // 静音超时，自动停止
                        uiHandler.post { stopVadRecording() }
                        break
                    }
                } else {
                    silenceStart = -1L
                }
            }
        }
    }

    private fun stopVadRecording() {
        if (!isVadRecording) return
        isVadRecording = false
        vadStopRequested = true
        vadMonitorThread?.interrupt()
        vadMonitorThread = null
        // stopAndTranscribe 内部恢复按钮背景和文字
        stopAndTranscribe()
    }

    /** 开始录音，返回是否成功启动 */
    private fun startRecording(): Boolean {
        if (!recorder.start()) {
            toast("录音启动失败，请检查麦克风权限")
            return false
        }
        recordStartMs = System.currentTimeMillis()
        uiHandler.post {
            // 红色背景提示录音中（按钮文字保持 🎤，与拼音键盘空格录音的红色圆底呼应）
            voiceBtn?.setBackgroundColor(0xFFE53935.toInt())
        }
        return true
    }

    private fun stopAndTranscribe() {
        uiHandler.post {
            // 恢复按钮文字和原始背景色
            voiceBtn?.text = "🎤"
            voiceBtn?.setBackgroundColor(voiceBtnOrigBgColor)
        }
        val pcm = recorder.stopAndGetPcm()
        if (pcm.isEmpty()) {
            toast("录音太短")
            return
        }
        val durMs = System.currentTimeMillis() - recordStartMs
        val backend = pickBackend() ?: return
        // 判断引擎类型（用于日志）
        val engineName = when (backend) {
            is com.icegps.voiceime.service.asr.SherpaAsrBackend -> "ondevice"
            else -> "lan"
        }
        toast("转写中…")
        thread {
            try {
                val result = backend.transcribe(pcm)
                val text = result.text.trim()
                // 保存语音 + 转写记录（供 Syncthing 同步到 PC）
                if (text.isNotEmpty()) {
                    TranscriptLogger.log(this, pcm, text, engineName, durMs)
                }
                uiHandler.post {
                    if (text.isNotEmpty()) {
                        // 系统原生上屏：光标处插入，不覆盖，不进候选栏
                        currentInputConnection?.commitText(text, 1)
                    } else {
                        toast("没听清，请再说一遍")
                    }
                }
            } catch (e: Exception) {
                uiHandler.post { toast("转写失败：${e.message}") }
            }
        }
    }

    private fun switchToPreviousIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // API 28+：直接切回上一个输入法
                switchToPreviousInputMethod()
            } else {
                // 低版本：弹出输入法选择器
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        } catch (e: Exception) {
            toast("切换失败，请手动切换输入法")
        }
    }

    /** 与 FloatBallService 同一套后端选择逻辑 */
    private fun pickBackend(): AsrBackend? {
        fun lanOrNull(): LanAsrBackend? {
            val host = Prefs.getHost(this)
            if (host.isEmpty()) {
                toast("请先在设置里填写 PC 服务端 IP")
                return null
            }
            return LanAsrBackend(host, Prefs.getPort(this))
        }
        return when (Prefs.getEngine(this)) {
            Prefs.ENGINE_LAN_ONLY -> lanOrNull()
            else -> {
                val sherpa = sherpaBackend()
                if (sherpa.isAvailable()) sherpa
                else {
                    toast("端侧模型未就绪，已降级到局域网转写")
                    lanOrNull()
                }
            }
        }
    }

    private fun sherpaBackend(): SherpaAsrBackend {
        var b = sherpaBackend
        if (b == null) {
            b = SherpaAsrBackend(applicationContext)
            sherpaBackend = b
        }
        return b
    }

    private fun toast(msg: String) {
        uiHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        backspaceRepeating = false
        backspaceHandler.removeCallbacks(backspaceRepeat)
        try { sherpaBackend?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
