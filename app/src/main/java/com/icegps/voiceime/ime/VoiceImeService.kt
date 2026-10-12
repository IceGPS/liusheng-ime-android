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
import com.icegps.voiceime.service.asr.AsrBackend
import com.icegps.voiceime.service.asr.LanAsrBackend
import com.icegps.voiceime.service.asr.MnnAsrBackend
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
    private var mnnBackend: MnnAsrBackend? = null

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
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
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
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.5f
                    v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    startRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    stopAndTranscribe()
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

        // 切回上一个输入法（放最左：不常用）
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

        row.addView(switchBtn)
        row.addView(voiceBtn)
        row.addView(backBtn)
        row.addView(enterBtn)
        return row
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private var voiceBtn: Button? = null

    private fun startRecording() {
        if (!recorder.start()) {
            toast("录音启动失败，请检查麦克风权限")
            return
        }
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
        val backend = pickBackend() ?: return
        toast("转写中…")
        thread {
            try {
                val result = backend.transcribe(pcm)
                val text = result.text.trim()
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
                val mnn = mnnBackend()
                if (mnn.isAvailable()) mnn
                else {
                    toast("端侧模型未就绪，已降级到局域网转写")
                    lanOrNull()
                }
            }
        }
    }

    private fun mnnBackend(): MnnAsrBackend {
        var b = mnnBackend
        if (b == null) {
            b = MnnAsrBackend(applicationContext)
            mnnBackend = b
        }
        return b
    }

    private fun toast(msg: String) {
        uiHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    override fun onDestroy() {
        backspaceRepeating = false
        backspaceHandler.removeCallbacks(backspaceRepeat)
        try { mnnBackend?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
