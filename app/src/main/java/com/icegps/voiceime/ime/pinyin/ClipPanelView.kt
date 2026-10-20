package com.icegps.voiceime.ime.pinyin

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.icegps.voiceime.service.clip.ClipEntry
import com.icegps.voiceime.service.clip.ClipStore

/**
 * 剪贴板历史面板（V69）。
 *
 * 打开时替换整个拼音键盘区域（含底行），候选栏保持可见；
 * 自带底部工具栏（清空 / 关闭）。
 *
 * - 点击条目：上屏并自动关闭（由 controller 接入 commitText）。
 * - 长按条目：弹出操作菜单（置顶/取消置顶、删除）。
 * - 数据来自 ClipStore；历史变化时若面板正开着会自动刷新。
 */
class ClipPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 点条目：上屏（controller 接入，先清拼音组合再 commitText） */
    var onEntryClick: ((ClipEntry) -> Unit)? = null

    /** 点关闭 */
    var onClose: (() -> Unit)? = null

    private val rowsContainer: LinearLayout
    private val scrollView: ScrollView
    private val emptyView: TextView
    private val titleView: TextView

    private val isDark = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    private val hintColor = 0xFF8E8E93.toInt()
    private val panelBg = if (isDark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
    private val rowBg = if (isDark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()

    /** 未开启功能时的提示（由外部设置） */
    var featureDisabledHint: Boolean = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 面板高度上限 = 父容器（输入视图 root）的真实可用高度（V77）。
     *
     * V75/V76 用「屏幕高度 50%」做上限，有两个问题：
     * 1. heightPixels 是整块物理屏高，IME 窗口真正可见的高度更小；
     *    面板高度 + 52dp 候选栏会超过窗口可见区，底部被窗口裁掉。
     * 2. clamp 在 super.onMeasure 之后用 setMeasuredDimension 硬改，
     *    ScrollView 已按未截断的高度量完，视口比实际可见区大，
     *    滚到底最后一条也露不全（真机报障：上滑只能露出一点点）。
     *
     * 改法：上限取父容器上次布局的实测高度（键盘模式下窗口已证明能
     * 完整显示的高度），减去父容器纵向 padding 与其它 VISIBLE 兄弟
     * （候选栏）的实测高度——即面板替换掉的键盘区高度；超限时用
     * EXACTLY 上限重新 measure 一次，让 ScrollView 按最终高度量准。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val maxH = availableHeightCap(heightMeasureSpec)
        if (maxH > 0 && measuredHeight > maxH) {
            super.onMeasure(
                widthMeasureSpec,
                MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.EXACTLY),
            )
        }
    }

    /**
     * 面板可占的最大高度。
     * 优先：父容器实测高度 − 纵向 padding − VISIBLE 兄弟实测高度。
     * 兜底：父给的高度 spec 大小（窗口的 AT_MOST 约束，已扣兄弟占用）。
     * 都拿不到返回 0（不限制）。
     */
    private fun availableHeightCap(heightMeasureSpec: Int): Int {
        val parentView = parent as? View
        if (parentView != null && parentView.measuredHeight > 0) {
            var cap = parentView.measuredHeight -
                parentView.paddingTop - parentView.paddingBottom
            val group = parentView as? ViewGroup
            if (group != null) {
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    if (child !== this && child.visibility == View.VISIBLE) {
                        cap -= child.measuredHeight
                    }
                }
            }
            if (cap > 0) return cap
        }
        val specSize = MeasureSpec.getSize(heightMeasureSpec)
        return if (specSize > 0) specSize else 0
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(panelBg)
        setPadding(dp(8), dp(4), dp(8), dp(4))

        // 顶部标题（带总条数，refresh 时更新，V77）
        titleView = TextView(context).apply {
            text = "剪贴板历史"
            textSize = 14f
            setTextColor(hintColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 空状态
        emptyView = TextView(context).apply {
            textSize = 15f
            setTextColor(hintColor)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }

        // 列表（滚动）：滚动条常显（V76）；内容超高时在面板限高内滚动
        rowsContainer = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            addView(rowsContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(scrollView, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // 底部工具栏：清空 | 关闭
        val toolbar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        val clearBtn = Button(context).apply {
            text = "清空"
            textSize = 15f
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                ClipStore.clearUnpinned()
            }
        }
        val closeBtn = Button(context).apply {
            text = "关闭"
            textSize = 15f
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClose?.invoke()
            }
        }
        toolbar.addView(clearBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(6)
        })
        toolbar.addView(closeBtn, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6)
        })
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** 重建列表（打开面板时、历史变化时调用） */
    fun refresh() {
        rowsContainer.removeAllViews()
        // 标题带总条数（V77）：便于核对显示条数与实际存储是否一致
        val entries = ClipStore.list()
        titleView.text = "剪贴板历史 · 共 ${entries.size} 条"
        if (featureDisabledHint) {
            emptyView.text = "剪贴板历史未开启\n请在留声设置中开启后使用"
            showEmpty()
            return
        }
        // 展示全部历史（DB 上限 50 条，V76）；面板限高 + 内部滚动
        if (entries.isEmpty()) {
            emptyView.text = "暂无剪贴板历史\n在任意应用中复制文本后会自动记录"
            showEmpty()
            return
        }
        rowsContainer.addView(emptyView)
        emptyView.visibility = View.GONE
        entries.forEach { entry -> rowsContainer.addView(buildRow(entry)) }
        scrollView.scrollTo(0, 0)
    }

    private fun showEmpty() {
        rowsContainer.addView(emptyView)
        emptyView.visibility = View.VISIBLE
    }

    private fun buildRow(entry: ClipEntry): View {
        val row = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(rowBg)
            val p = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            p.setMargins(0, dp(3), 0, dp(3))
            layoutParams = p
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = false
        }
        val pinMark = if (entry.pinned) "📌 " else ""
        val textTv = TextView(context).apply {
            text = entry.text
            textSize = 16f
            setTextColor(textColor)
            // 强制最多 2 行（宽度显式 MATCH_PARENT，保证多段长文本也被 ellipsize 压住，V75）
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        val sourceLabel = if (entry.source == ClipStore.SOURCE_LOCAL) "本机" else "来自 ${entry.source}"
        val metaTv = TextView(context).apply {
            text = "$pinMark${relativeTime(entry.ts)} · $sourceLabel"
            textSize = 12f
            setTextColor(hintColor)
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        row.addView(textTv)
        row.addView(metaTv)
        row.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onEntryClick?.invoke(entry)
        }
        row.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showRowMenu(row, entry)
            true
        }
        return row
    }

    /** 长按菜单：置顶/取消置顶、删除（PopupWindow，输入法内可用） */
    private fun showRowMenu(anchor: View, entry: ClipEntry) {
        var popup: PopupWindow? = null
        val menu = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(rowBg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val pinBtn = Button(context).apply {
            text = if (entry.pinned) "取消置顶" else "📌 置顶"
            textSize = 15f
            setOnClickListener {
                ClipStore.setPinned(entry.id, !entry.pinned)
                popup?.dismiss()
            }
        }
        val delBtn = Button(context).apply {
            text = "🗑 删除"
            textSize = 15f
            setOnClickListener {
                ClipStore.delete(entry.id)
                popup?.dismiss()
            }
        }
        menu.addView(pinBtn)
        menu.addView(delBtn)
        popup = PopupWindow(menu, dp(180), LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(0x00000000))
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        try {
            popup.showAsDropDown(anchor, 0, -anchor.height)
        } catch (_: Exception) {
        }
    }

    private fun relativeTime(ts: Long): String {
        val diff = System.currentTimeMillis() - ts
        if (diff < 0) return "刚刚"
        val min = diff / 60000
        if (min < 1) return "刚刚"
        if (min < 60) return "${min}分钟前"
        val hour = min / 60
        if (hour < 24) return "${hour}小时前"
        val day = hour / 24
        if (day == 1L) return "昨天"
        if (day < 30) return "${day}天前"
        return "${day / 30}个月前"
    }

}
