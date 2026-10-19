package com.icegps.voiceime.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.icegps.voiceime.ime.pinyin.CandidateBarView
import com.icegps.voiceime.ime.pinyin.PinyinDictionary
import com.icegps.voiceime.ime.pinyin.PinyinInputController
import com.icegps.voiceime.ime.pinyin.ShuangpinMapper
import com.icegps.voiceime.ime.pinyin.SymbolKeyboardView
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
 * 界面（V68 统一底行 7 键，与其他三套键盘一字不差）：
 *  - ABC：点按切拼音键盘，长按 500ms 调系统输入法选择器
 *  - 符 / 123：直达符号 / 数字键盘
 *  - 🎤 语音键（兼空格键）：短按输入空格，长按语音输入（不限按压位置）；
 *    松开转写上屏（走系统 commitText，光标天然正确）
 *  - 。：点按=句号，上滑=逗号（固定全角，与语音转写输出一致）
 *  - ⌫ 点按删一个字符，长按连续删除
 *  - ⏎ 回车换行
 *
 * 转写复用现有后端（局域网 / 端侧 MNN），与悬浮球同一套逻辑。
 */
class VoiceImeService : InputMethodService() {

    override fun onCreate() {
        super.onCreate()
        // 启动时积极加载所有重组件（不用懒加载）：后台线程按优先级顺序预热，
        // 强引用占住内存，避免 GC 释放后再次懒加载卡顿。
        // 顺序：拼音词典（27MB）→ 双拼映射表 → 语音模型（~230MB，局域网模式跳过）。
        Thread {
            try {
                PinyinDictionary.get(this)
            } catch (_: Exception) {
            }
            try {
                ShuangpinMapper.warmUp()
            } catch (_: Exception) {
            }
            try {
                if (com.icegps.voiceime.util.Prefs.getEngine(this) !=
                    com.icegps.voiceime.util.Prefs.ENGINE_LAN_ONLY
                ) {
                    sherpaBackend().warmUp()
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; name = "ime-warmup" }.start()
    }

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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 横屏/竖屏切换、折叠屏开合时重建输入视图
        setInputView(onCreateInputView())
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

        // 候选栏：拼音模式下显示（候选词横滚；拼音小耳朵为悬浮气泡，不占栏内空间）。
        // 高度固定 52dp（对齐搜狗输入法候选栏高度）：输入法窗口高度恒定，
        // 目标 App 编辑区不随输入状态伸缩闪烁。
        val candidateBar = CandidateBarView(ctx).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            )
        }
        this.candidateBar = candidateBar

        // 语音模式容器：装主输入行（V68 统一底行 7 键）
        val voiceBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        this.voiceBox = voiceBox

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 语音键（兼空格键）：短按输入空格，长按语音输入（不限按压位置）
        val voiceBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "🎤"
            textSize = 20f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 2.8f).apply {
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
            // 长按触发：手指未松开且未滑出时转入语音录音，轻振提示已进入录音
            if (voiceTapValid && !voiceRecording) {
                voiceTapValid = false
                if (startRecording()) {
                    voiceRecording = true
                    voiceBtn?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
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

        // V68：。 键（点按=句号，上滑=逗号；固定全角——极简键盘无中/英切换 UI，
        // 跟随看不见的状态会让用户困惑；且语音转写输出中文+全角标点，手动补标点保持全角才一致）
        // 位置：空格键与退格键之间（四套键盘统一底行）
        val punctBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "。"
            textSize = 20f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(2)
            }
        }
        var punctDownY = 0f
        var punctSwiped = false
        punctBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    punctDownY = event.y
                    punctSwiped = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // 上滑约半个键高进入逗号模式，轻振提示
                    if (!punctSwiped && punctDownY - event.y > dp(28)) {
                        punctSwiped = true
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    v.performClick()
                    // 固定全角（不跟随中/英，见上方注释）
                    currentInputConnection?.commitText(if (punctSwiped) "，" else "。", 1)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        // 退格键：点按删一个，长按连续删
        val backBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "⌫"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1.2f).apply {
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

        // （V67 统一底行：⚙️ 设置键退役，设置入口改走候选栏 🎤；VAD 只留设置页内开关）

        // （V67 统一底行：🌐 退役，长按 ABC 键调系统输入法选择器代替）

        // 回车键：换行（放最右）
        val enterBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "⏎"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1.4f)
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

        // V67 统一底行：ABC / 符 / 123（与其他三套键盘一字不差）
        // ABC 键：点按切拼音键盘；长按 500ms 调系统输入法选择器（替代退役的 🌐）
        val abcBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "ABC"
            textSize = 20f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1.2f).apply {
                marginEnd = dp(2)
            }
        }
        var abcDownTime = 0L
        abcBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    abcDownTime = android.os.SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    if (android.os.SystemClock.uptimeMillis() - abcDownTime > 500) {
                        v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                    } else {
                        v.performClick()
                        showPinyinKeyboard()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        // 符键：直达符号键盘
        val symbolBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "符"
            textSize = 20f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(2)
            }
        }
        symbolBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    v.performClick()
                    voiceBox?.visibility = View.GONE
                    pinyinController?.showSymbolKeyboard()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        // 123 键：直达数字键盘
        val digitBtn = Button(ctx).apply {
            styleBtn(this, btnColor, textColor)
            text = "123"
            textSize = 20f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(2)
            }
        }
        digitBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.alpha = 1f
                    v.performClick()
                    voiceBox?.visibility = View.GONE
                    pinyinController?.showDigitKeyboard()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    true
                }
                else -> false
            }
        }

        // V67 统一底行：[ABC] [符] [123] [🎤] [⌫] [⏎]，与其他三套键盘一字不差
        // （宽屏也不再加 ，/。 extras，保底行统一）
        // V68：空格与 ⌫ 之间加 。 键 → [ABC] [符] [123] [🎤] [。] [⌫] [⏎]
        row.addView(abcBtn)
        row.addView(symbolBtn)
        row.addView(digitBtn)
        row.addView(voiceBtn)
        row.addView(punctBtn)
        row.addView(backBtn)
        row.addView(enterBtn)

        // V67：可收起的符号/数字区退役（V63 完整符号/数字键盘替代），voiceBox 只装主行
        voiceBox.addView(row)

        // 拼音键盘（默认隐藏）
        val pinyinKeyboard = PinyinKeyboardView(ctx).apply {
            visibility = View.GONE
        }
        this.pinyinKeyboard = pinyinKeyboard

        // 符号/数字键盘（默认隐藏，与拼音键盘互斥显示）
        val symbolKeyboard = SymbolKeyboardView(ctx).apply {
            visibility = View.GONE
        }
        this.symbolKeyboard = symbolKeyboard

        // 拼音输入控制器：键盘 / 候选栏事件 → InputConnection
        pinyinController = PinyinInputController(
            connectionProvider = { currentInputConnection },
            onSwitchToVoice = { showVoiceKeyboard() },
            onToast = { msg -> toast(msg) },
        ).apply {
            attach(pinyinKeyboard, candidateBar)
            attachSymbol(symbolKeyboard)
            // 接入真实引擎（词典 + 小鹤双拼，替代桩实现）
            setEngine(EngineAdapter(ctx))
            // 拼音键盘空格麦克风：按住录音、松开转写（与极简语音键盘同一套录音链路）
            onVoiceRecordStart = { startRecording() }
            onVoiceRecordStop = { stopAndTranscribe() }
            // 候选栏左上角 🎤：进入留声输入法设置界面
            onOpenSettings = {
                val intent = android.content.Intent(
                    ctx, com.icegps.voiceime.ui.SettingsActivity::class.java)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
        }

        // 顺序：候选栏、语音模式、拼音键盘、符号/数字键盘
        root.addView(candidateBar)
        root.addView(voiceBox)
        root.addView(pinyinKeyboard)
        root.addView(symbolKeyboard)

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
    private var symbolKeyboard: SymbolKeyboardView? = null
    private var pinyinController: PinyinInputController? = null

    /** 切换到拼音键盘（候选栏 + QWERTY），状态持久化 */
    private fun showPinyinKeyboard() {
        voiceBox?.visibility = View.GONE
        symbolKeyboard?.visibility = View.GONE
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
        symbolKeyboard?.visibility = View.GONE
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
