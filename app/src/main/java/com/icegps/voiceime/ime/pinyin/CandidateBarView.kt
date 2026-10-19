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
 * 拼音候选栏（V47，V53 改版）。
 *
 * 布局（横向单行，高度固定 52dp，对齐搜狗输入法，不随输入状态变化）：
 * - 左：🎤 按钮，点按进入留声输入法设置界面
 * - 中：横向滚动候选词列表
 * - 右：">" 按钮，点击展开浮层候选面板
 *
 * 拼音小耳朵：悬浮气泡（仿搜狗），浮在候选栏左上方，不占栏内横向空间；
 * 宽度随拼音长度动态变化，无拼音时隐藏。
 *
 * 高度固定是刻意设计：输入法窗口高度恒定，
 * 目标 App 的编辑区不会跟着伸缩闪烁。
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

    /** 点左上角 🎤：进入留声输入法设置界面 */
    var onOpenSettings: (() -> Unit)? = null

    /** 点右端"📋"：打开剪贴板历史面板（V69：最高频功能占最顺手的位置） */
    var onClipboardClick: (() -> Unit)? = null

    /** 长按右端"📋"：切换中英文输入（V69：中/英从点击改为长按） */
    var onLangToggle: (() -> Unit)? = null

    /** 点候选词：按下标选中上屏 */
    var onCandidateClick: ((index: Int) -> Unit)? = null

    /** 点右端 ">"：展开浮层候选面板 */
    var onExpandCandidates: (() -> Unit)? = null

    private val candidatesRow: LinearLayout
    private val expandBtn: TextView
    private val clipBtn: TextView

    /** 拼音小耳朵悬浮气泡 */
    private var earPopup: PopupWindow? = null
    private var earPopupTv: TextView? = null

    /** 浮层候选面板 */
    private var panelPopup: PopupWindow? = null

    private val isDark = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val hintColor = 0xFF8E8E93.toInt()
    private val panelBgColor = if (isDark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
    private val earBgColor = if (isDark) 0xFF3A3A3C.toInt() else 0xFFE8E8EA.toInt()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(if (isDark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt())
        val pad = dp(4)
        setPadding(pad, pad, pad, pad)

        // 左：进入设置
        val settingsBtn = TextView(context).apply {
            text = "🎤"
            textSize = 20f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onOpenSettings?.invoke()
            }
        }
        addView(settingsBtn, LayoutParams(dp(52), LayoutParams.MATCH_PARENT))

        // 中：横向滚动候选词
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
        addView(scroll, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        // 右：剪贴板（V69）：点击打开剪贴板历史面板，长按切换中/英。
        // 键面保留中/英文字（V67 的指示器传统）：中文"📋中"蓝色，英文"📋英"灰色。
        clipBtn = TextView(context).apply {
            text = "📋中"
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClipboardClick?.invoke()
            }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLangToggle?.invoke()
                true
            }
        }
        addView(clipBtn, LayoutParams(dp(56), LayoutParams.MATCH_PARENT))

        // 右：">" 展开按钮（始终占位，保持栏高稳定）
        expandBtn = TextView(context).apply {
            text = ">"
            textSize = 18f
            setTextColor(hintColor)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onExpandCandidates?.invoke()
            }
        }
        addView(expandBtn, LayoutParams(dp(40), LayoutParams.MATCH_PARENT))
    }

    /**
     * 设置中/英指示器（V67）：中文模式显示"📋中"（高亮），英文模式显示"📋英"。
     * V69：键面加上 📋（点击=剪贴板面板），长按切换中/英。
     */
    fun setLangIndicator(isChinese: Boolean) {
        clipBtn.text = if (isChinese) "📋中" else "📋英"
        // 高亮当前：中文用蓝色，英文用灰色（与拼音键盘原中/英键一致）
        clipBtn.setTextColor(if (isChinese) 0xFF007AFF.toInt() else hintColor)
    }

    /**
     * 设置拼音小耳朵文本（仿搜狗：悬浮气泡浮在候选栏左上方，宽度随拼音动态变化）。
     * 空则隐藏气泡。
     */
    fun setComposing(text: String) {
        if (text.isEmpty() || !isAttachedToWindow) {
            dismissEarBubble()
            return
        }
        val tv = earPopupTv
        if (earPopup?.isShowing == true && tv != null) {
            tv.text = text
            // 按新文本重新测量，只更新浮层尺寸，不动位置
            // （位置由 showAsDropDown 一次定好：锚定本栏上方）
            tv.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            try {
                earPopup?.update(tv.measuredWidth, tv.measuredHeight)
            } catch (e: Exception) {
                dismissEarBubble()
            }
        } else {
            showEarBubble(text)
        }
    }

    /** 显示拼音小耳朵悬浮气泡（本栏上方，左对齐到候选区） */
    private fun showEarBubble(text: String) {
        dismissEarBubble()
        if (!isAttachedToWindow || width == 0 || height == 0) return
        try {
            val tv = TextView(context).apply {
                this.text = text
                textSize = 14f
                setTextColor(hintColor)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(earBgColor)
                    // 上两角圆角、下两角直角（小耳朵/提示签形状）
                    val r = dp(8).toFloat()
                    cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                }
                setPadding(dp(10), dp(2), dp(10), dp(2))
                isFocusable = false
            }
            tv.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            )
            val w = tv.measuredWidth
            val h = tv.measuredHeight
            earPopupTv = tv
            earPopup = PopupWindow(tv, w, h, false).apply {
                // 纯展示气泡：不抢焦点、不拦截触摸（下面的话筒按钮仍可点）
                isTouchable = false
                isOutsideTouchable = false
                setBackgroundDrawable(ColorDrawable(0x00000000))
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            }
            // 显示在本栏上方：showAsDropDown 的 yoff 相对本栏底部计算，
            // 要浮到栏上方需再往上抬一个栏高：yoff = -(栏高 + 气泡高 + 间隙)；
            // x 取 60dp：越过左侧话筒按钮，左对齐到候选区（不盖话筒）
            earPopup?.showAsDropDown(this, dp(60), -(height + h + dp(4)))
        } catch (e: Exception) {
            // 悬浮失败不影响主流程（候选栏本身仍可用）
            dismissEarBubble()
        }
    }

    /** 关闭拼音小耳朵悬浮气泡（已关闭时无操作） */
    fun dismissEarBubble() {
        earPopup?.dismiss()
        earPopup = null
        earPopupTv = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissEarBubble()
        dismissCandidatePanel()
    }

    /** 设置候选词列表（重建全部候选项，不限数量；自动去重） */
    fun setCandidates(list: List<String>) {
        // 候选变化时收起浮层（避免显示过期候选）
        dismissCandidatePanel()
        candidatesRow.removeAllViews()
        list.distinct().forEachIndexed { index, word ->
            val tv = TextView(context).apply {
                text = word
                textSize = 22f
                setTextColor(textColor)
                gravity = Gravity.CENTER
                minWidth = dp(48)
                // 纵向内边距 4dp：52dp 栏高内容纳得下（不裁字），
                // 且栏内上下留白比原来减半
                setPadding(dp(14), dp(4), dp(14), dp(4))
                isClickable = true
                isFocusable = false
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onCandidateClick?.invoke(index)
                }
            }
            candidatesRow.addView(tv)
        }
        // ">" 按钮始终占位（V47：固定栏高，不再随候选有无显示/隐藏）
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
            panelPopup = PopupWindow(content, width, h, false).apply {
                // focusable=false：可聚焦的 PopupWindow 在输入法窗口内会抢焦点，
                // 导致 InputConnection 状态异常、setComposingText 失效回退为直接提交
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
