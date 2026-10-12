package com.icegps.voiceime.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.icegps.voiceime.util.Prefs

/**
 * 权限引导页（V6 起：极简 IME 版）。
 *
 * 只需要两项：
 *  1. 麦克风权限：录音
 *  2. 启用输入法：在系统设置里打开 voice-ime 输入法
 *
 * 不再需要：悬浮窗、无障碍、电池白名单、自启动。
 */
class OnboardingActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(title("使用前请完成以下两步"))
        root.addView(desc("voice-ime 现在是系统输入法，只需要麦克风 + 启用输入法。"))

        root.addView(stepCard(
            "1. 麦克风权限",
            "用来录制你的语音。",
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1001)
        })

        root.addView(stepCard(
            "2. 启用 voice-ime 输入法",
            "去 系统设置 → 语言和输入法 → 屏幕键盘，打开「voice-ime」。\n" +
                "之后在输入时点键盘切换图标即可换到它。",
        ) {
            try {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } catch (e: Exception) {
                toast("无法打开输入法设置，请手动前往")
            }
        })

        root.addView(Button(this).apply {
            text = "完成，进入主界面"
            setOnClickListener {
                if (hasMic()) {
                    Prefs.setOnboarded(this@OnboardingActivity)
                    finish()
                } else {
                    toast("请先授予麦克风权限")
                }
            }
        }.apply {
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            p.topMargin = dp(16)
            layoutParams = p
        })
    }

    override fun onResume() {
        super.onResume()
        // 回到本页时刷新输入法状态提示
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        if (enabled) toast("输入法已启用 ✓")
    }

    private fun hasMic(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---------- 小工具 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 20f
        setTextColor(Color.BLACK)
        setPadding(0, 0, 0, dp(8))
    }

    private fun desc(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(Color.DKGRAY)
        setPadding(0, 0, 0, dp(12))
    }

    private fun stepCard(title: String, desc: String, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(Color.BLACK)
            })
            addView(TextView(context).apply {
                text = desc
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(4), 0, dp(8))
            })
            addView(Button(context).apply {
                text = "去设置"
                setOnClickListener { onClick() }
            })
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            p.topMargin = dp(8)
            layoutParams = p
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
