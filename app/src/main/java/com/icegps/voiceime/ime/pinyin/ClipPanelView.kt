package com.icegps.voiceime.ime.pinyin

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
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
     * 面板高度上限（屏幕高度的一半）：无论内容多长都不撑爆 IME 输入窗口（V75）。
     * 内部 ScrollView 会在剩余空间内滚动。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val maxH = (resources.displayMetrics.heightPixels * MAX_HEIGHT_FRACTION).toInt()
        if (measuredHeight > maxH) {
            setMeasuredDimension(measuredWidth, maxH)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(panelBg)
        setPadding(dp(8), dp(4), dp(8), dp(4))

        // 顶部标题
        val title = TextView(context).apply {
            text = "剪贴板历史"
            textSize = 14f
            setTextColor(hintColor)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

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
        if (featureDisabledHint) {
            emptyView.text = "剪贴板历史未开启\n请在留声设置中开启后使用"
            showEmpty()
            return
        }
        // 展示全部历史（DB 上限 50 条，V76）；面板限高 + 内部滚动
        val entries = ClipStore.list()
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

    companion object {
        /** 面板高度上限占屏幕高度的比例（V75 引入，V76 配合内部滚动展示全部条目） */
        private const val MAX_HEIGHT_FRACTION = 0.5f
    }
}
