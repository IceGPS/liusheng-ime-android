package com.icegps.voiceime.ime.pinyin

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.View.MeasureSpec
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/**
 * 拼音候选栏。
 *
 * 布局（横向）：
 * - 左：🎤 按钮，点按切回语音键行
 * - 中（垂直）：拼音组合区（如 "ni'hao"，无内容时隐藏）+ 横向滚动候选词列表
 * - 右：">" 按钮，有候选词时显示，点击展开浮层候选面板
 *
 * 候选浏览两种方式：
 * 1. 横向滑动候选栏（HorizontalScrollView，右边缘渐隐提示还有更多）
 * 2. 点 ">" 弹出浮层面板（PopupWindow，锚定在本栏上方，不替换键盘）
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

    /** 点右端 ">"：展开浮层候选面板 */
    var onExpandCandidates: (() -> Unit)? = null

    private val composingTv: TextView
    private val candidatesRow: LinearLayout
    private val expandBtn: TextView

    /** 浮层候选面板 */
    private var panelPopup: PopupWindow? = null

    private val isDark = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val hintColor = 0xFF8E8E93.toInt()
    private val panelBgColor = if (isDark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

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

        // 中：垂直排布组合区 + 候选词横滚
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
            // 右边缘渐隐：提示后面还有更多候选词
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(24))
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

        // 右：">" 展开按钮（有候选词时显示）
        expandBtn = TextView(context).apply {
            text = ">"
            textSize = 18f
            setTextColor(hintColor)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            visibility = GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onExpandCandidates?.invoke()
            }
        }
        addView(expandBtn, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
    }

    /** 设置拼音组合区文本（空则隐藏该行） */
    fun setComposing(text: String) {
        composingTv.text = text
        composingTv.visibility = if (text.isEmpty()) GONE else VISIBLE
    }

    /** 设置候选词列表（重建全部候选项，不限数量） */
    fun setCandidates(list: List<String>) {
        // 候选变化时收起浮层（避免显示过期候选）
        dismissCandidatePanel()
        candidatesRow.removeAllViews()
        list.forEachIndexed { index, word ->
            val tv = TextView(context).apply {
                text = word
                textSize = 22f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                minWidth = dp(48)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onCandidateClick?.invoke(index)
                }
            }
            candidatesRow.addView(tv)
        }
        // 有候选词时才显示 ">" 展开按钮
        expandBtn.visibility = if (list.isEmpty()) GONE else VISIBLE
    }

    /**
     * 显示浮层候选面板。
     *
     * PopupWindow 锚定在本栏上方（不替换键盘），宽度与本栏一致；
     * 顶部显示拼音组合文本，下方网格排列全部候选词（每行 4 个），
     * 内容超高时内部垂直滚动。
     *
     * @param candidates 全部候选词（不截断）
     * @param composing 当前拼音组合文本（如 "yi'yi"）
     * @param onSelect 按**绝对下标**选中；选中后自动关闭浮层
     */
    fun showCandidatePanel(
        candidates: List<String>,
        composing: String,
        onSelect: (Int) -> Unit,
    ) {
        dismissCandidatePanel()
        if (candidates.isEmpty() || width == 0) return
        try {
            val content = buildPanelContent(candidates, composing, onSelect)
            // 测量内容高度，限制最大高度（拼音行 + 约 5 行候选）
            content.measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val maxH = dp(44 + 5 * 56)
            val h = minOf(content.measuredHeight, maxH).coerceAtLeast(dp(100))
            panelPopup = PopupWindow(content, width, h, true).apply {
                // 点击外部关闭（必须配 background 才生效）
                isOutsideTouchable = true
                setBackgroundDrawable(ColorDrawable(0x00000000))
                // 不干扰输入法窗口
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            }
            // 显示在本栏上方：y 偏移 = -(本栏高 + 浮层高)
            panelPopup?.showAsDropDown(this, 0, -height - h)
        } catch (e: Exception) {
            // 浮层失败不影响主流程（候选栏本身仍可用）
            dismissCandidatePanel()
        }
    }

    /** 关闭浮层候选面板（已关闭时无操作） */
    fun dismissCandidatePanel() {
        panelPopup?.dismiss()
        panelPopup = null
    }

    /** 浮层候选面板是否正在显示 */
    fun isCandidatePanelShowing(): Boolean = panelPopup?.isShowing == true

    /**
     * 构建浮层内容：顶部拼音行 + 网格候选区。
     * 网格每行 4 个候选词，不足补空白占位保持对齐。
     */
    private fun buildPanelContent(
        candidates: List<String>,
        composing: String,
        onSelect: (Int) -> Unit,
    ): View {
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(panelBgColor)
                cornerRadius = dp(12).toFloat()
            }
            // 顶部留出圆角视觉空间
            setPadding(0, dp(4), 0, dp(8))
        }

        // 顶部：当前拼音组合文本
        if (composing.isNotEmpty()) {
            val pinTv = TextView(context).apply {
                text = composing
                textSize = 14f
                setTextColor(hintColor)
                setPadding(dp(16), dp(8), dp(16), dp(4))
            }
            root.addView(
                pinTv,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
            )
        }

        // 网格：每行 4 个候选
        val cols = 4
        val grid = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        var row: LinearLayout? = null
        candidates.forEachIndexed { index, word ->
            if (index % cols == 0) {
                row = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                grid.addView(
                    row,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                )
            }
            val tv = TextView(context).apply {
                text = word
                textSize = 22f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(12), dp(8), dp(12))
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onSelect(index) // 绝对下标
                }
            }
            row?.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        // 最后一行不足 4 个时补空白占位，保持列对齐
        val remainder = candidates.size % cols
        if (remainder != 0) {
            repeat(cols - remainder) {
                row?.addView(View(context), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        // 垂直滚动容器（内容超高时滚动）
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        root.addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        return root
    }
}
