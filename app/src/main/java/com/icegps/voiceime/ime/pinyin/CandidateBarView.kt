package com.icegps.voiceime.ime.pinyin

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 拼音候选栏。
 *
 * 布局（横向）：
 * - 左：🎤 按钮，点按切回语音键行
 * - 右（垂直）：拼音组合区（如 "ni'hao"，无内容时隐藏）+ 横向滚动候选词列表
 *
 * 在拼音键盘模式下常驻显示（候选为空时只剩 🎤 按钮）。
 */
class CandidateBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点左上角 🎤：切回语音键行 */
    var onToggleVoice: (() -> Unit)? = null

    /** 点候选词：按下标选中上屏 */
    var onCandidateClick: ((index: Int) -> Unit)? = null

    private val composingTv: TextView
    private val candidatesRow: LinearLayout

    private val isDark = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val hintColor = 0xFF8E8E93.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(if (isDark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt())
        val pad = dp(4)
        setPadding(pad, pad, pad, pad)

        // 左：切回语音键行
        val toggle = TextView(context).apply {
            text = "🎤"
            textSize = 20f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onToggleVoice?.invoke()
            }
        }
        addView(toggle, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))

        // 右：垂直排布组合区 + 候选词横滚
        val right = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        composingTv = TextView(context).apply {
            textSize = 13f
            setTextColor(hintColor)
            setPadding(dp(10), dp(2), dp(8), dp(2))
            visibility = GONE
        }
        right.addView(
            composingTv,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        candidatesRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroll.addView(
            candidatesRow,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        right.addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(right, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    /** 设置拼音组合区文本（空则隐藏该行） */
    fun setComposing(text: String) {
        composingTv.text = text
        composingTv.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    /** 设置候选词列表（重建全部候选项） */
    fun setCandidates(list: List<String>) {
        candidatesRow.removeAllViews()
        list.forEachIndexed { index, word ->
            val tv = TextView(context).apply {
                text = word
                textSize = 19f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                minWidth = dp(48)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onCandidateClick?.invoke(index)
                }
            }
            candidatesRow.addView(tv)
        }
    }
}
