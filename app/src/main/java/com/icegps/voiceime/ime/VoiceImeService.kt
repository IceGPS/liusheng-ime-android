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
import android.widget.LinearLayout
import android.widget.Toast
import com.icegps.voiceime.service.Recorder
import com.icegps.voiceime.service.TranscriptLogger
import com.icegps.voiceime.service.asr.AsrBackend
import com.icegps.voiceime.service.asr.LanAsrBackend
import com.icegps.voiceime.service.asr.SherpaAsrBackend
import com.icegps.voiceime.util.Prefs
import kotlin.concurrent.thread

/**
 * 极简语音输入法。
 *
 * 界面只有三个键：
 *  - 🎤 按住说话，松开转写上屏（走系统 commitText，光标天然正确）
 *  - ⌫ 点按删一个字符，长按连续删除
 *  - 🌐 切回上一个输入法
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
        // 外层垂直布局：符号行、数字行（可折叠，默认关闭）、主输入行
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // 数字/标点区开关：最左边，空心上箭头
        val numpadToggleBtn = Button(ctx).apply {
            text = if (Prefs.isNumPadExpanded(ctx)) "⇩" else "⇧"
            textSize = 20f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(8)
            }
        }

        // 语音键：按住录音，松开转写
        val voiceBtn = Button(ctx).apply {
            text = "🎤 按住说话"
            textSize = 16f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 2f).apply {
                marginEnd = dp(8)
            }
        }
        this.voiceBtn = voiceBtn
        voiceBtn.setOnTouchListener { v, event ->
            val vadEnabled = Prefs.isVadEnabled(this)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    if (vadEnabled) {
                        // VAD 模式：点按切换
                        if (isVadRecording) {
                            stopVadRecording()
                        } else {
                            startVadRecording()
                        }
                    } else {
                        startRecording()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    if (!vadEnabled) {
                        stopAndTranscribe()
                    }
                    // VAD 模式下 UP 不做任何事（靠点按切换或自动停止）
                    true
                }
                else -> false
            }
        }

        // 退格键：点按删一个，长按连续删
        val backBtn = Button(ctx).apply {
            text = "⌫"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(8)
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
            text = "⚙️"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(8)
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
            text = "🌐"
            textSize = 22f
            isHapticFeedbackEnabled = true
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(8)
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

        row.addView(numpadToggleBtn)
        row.addView(settingsBtn)
        row.addView(switchBtn)
        row.addView(voiceBtn)
        row.addView(backBtn)
        row.addView(enterBtn)

        // 数字/标点区：两行，默认折叠，放在主输入行上面
        // 第一行：加减乘除，等号，百分号，逗号，句号，问号，下划线
        val symbolRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (Prefs.isNumPadExpanded(ctx)) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(4))
        }
        // 第二行：0-9 数字
        val digitRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = if (Prefs.isNumPadExpanded(ctx)) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, dp(4))
        }
        fun makeCharBtn(ch: String): Button {
            val b = Button(ctx).apply {
                text = ch
                textSize = 18f
                isHapticFeedbackEnabled = true
                layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                    marginEnd = dp(4)
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
        // 第一行 10 个符号：+ - × ÷ = % ，。？ _
        for (ch in listOf("+","-","×","÷","=","%","，","。","？","_")) {
            symbolRow.addView(makeCharBtn(ch))
        }
        // 第二行 10 个数字：0-9
        for (ch in listOf("0","1","2","3","4","5","6","7","8","9")) {
            digitRow.addView(makeCharBtn(ch))
        }

        // 开关点击：展开/折叠
        numpadToggleBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    val expanded = symbolRow.visibility == View.VISIBLE
                    if (expanded) {
                        symbolRow.visibility = View.GONE
                        digitRow.visibility = View.GONE
                        (v as Button).text = "⇧"
                        Prefs.setNumPadExpanded(ctx, false)
                    } else {
                        symbolRow.visibility = View.VISIBLE
                        digitRow.visibility = View.VISIBLE
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

        // 顺序：符号行、数字行、主输入行
        root.addView(symbolRow)
        root.addView(digitRow)
        root.addView(row)
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var voiceBtn: Button? = null

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
        uiHandler.post { voiceBtn?.text = "🎤 VAD 录音中…点按停止" }

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
        uiHandler.post { voiceBtn?.text = "🎤 按住说话" }
        stopAndTranscribe()
    }

    private fun startRecording() {
        if (!recorder.start()) {
            toast("录音启动失败，请检查麦克风权限")
            return
        }
        recordStartMs = System.currentTimeMillis()
        uiHandler.post {
            voiceBtn?.text = "🎤 录音中…"
        }
    }

    private fun stopAndTranscribe() {
        uiHandler.post { voiceBtn?.text = "🎤 按住说话" }
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
