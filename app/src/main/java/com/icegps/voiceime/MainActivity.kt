package com.icegps.voiceime

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.icegps.voiceime.service.ServerClient
import com.icegps.voiceime.service.asr.SherpaAsrBackend
import com.icegps.voiceime.ui.OnboardingActivity
import com.icegps.voiceime.ui.SettingsActivity
import com.icegps.voiceime.util.BuildInfo
import com.icegps.voiceime.util.Prefs

/**
 * 主界面：状态总览 + 入口按钮。
 *
 * V6：极简 IME 版。不再有悬浮球/无障碍，
 * 上屏走系统输入法通道（commitText）。
 */
class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(titleView("留声输入法（极简输入法）"))
        root.addView(descView("🎤 按住说话，松开上屏\n⌫ 退格删除\n🌐 一键切回原输入法"))
        root.addView(descView(
            "版本：${BuildInfo.VERSION_NAME} (${BuildInfo.VERSION_CODE})\n" +
            "构建：${BuildInfo.BUILD_DATE}\n" +
            "提交：${BuildInfo.GIT_SHA}"
        ))
        statusView = descView("")
        root.addView(statusView)

        root.addView(space())
        root.addView(btn("1. 权限引导（首次必做）") {
            startActivity(Intent(this, OnboardingActivity::class.java))
        })
        root.addView(btn("2. 设置：转写引擎 / 服务端") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(btn("3. 启用输入法") {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (e: Exception) {
                toast("无法打开，请手动前往 系统设置 → 语言和输入法")
            }
        })
        root.addView(btn("测试服务端连接") { testConnection() })

        setContentView(scroll)

        if (!Prefs.isOnboarded(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val imeEnabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val engine = if (Prefs.getEngine(this) == Prefs.ENGINE_LAN_ONLY) "仅局域网" else "端侧优先"
        val modelState = when {
            Prefs.getEngine(this) == Prefs.ENGINE_LAN_ONLY -> "（不使用）"
            SherpaAsrBackend.isModelReady(this) ->
                "已就绪（${SherpaAsrBackend.modelSizeBytes(this) / 1024 / 1024}MB）"
            else -> "未下载"
        }
        val host = Prefs.getHost(this).ifEmpty { "（未设置）" }
        statusView.text =
            "状态：\n" +
            "- 输入法已启用：${if (imeEnabled) "是 ✓" else "否（请去第 3 步启用）"}\n" +
            "- 转写引擎：$engine\n" +
            "- 端侧模型：$modelState\n" +
            "- 服务端：$host:${Prefs.getPort(this)}"
    }

    /** 发一个空转写请求验证连通性（只建连，不发音频） */
    private fun testConnection() {
        val host = Prefs.getHost(this)
        if (host.isEmpty()) {
            toast("请先在设置里填写 PC 服务端 IP")
            return
        }
        toast("正在连接 $host:${Prefs.getPort(this)} …")
        Thread({
            val ok = ServerClient.checkConnection(host, Prefs.getPort(this))
            ServerClient.onMain {
                toast(if (ok) "连接正常" else "连接失败：请检查 IP/端口，确认 PC 服务端在运行且手机与 PC 在同一局域网")
            }
        }, "voice-ime-test").start()
    }

    // ---------- 小工具 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun titleView(t: String) = TextView(this).apply {
        text = t
        textSize = 20f
        setTextColor(Color.BLACK)
        setPadding(0, 0, 0, dp(8))
    }

    private fun descView(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.DKGRAY)
        setPadding(0, 0, 0, dp(8))
    }

    private fun space() = TextView(this).apply { text = "" }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        val p = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        p.topMargin = dp(6)
        layoutParams = p
        setOnClickListener { onClick() }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
