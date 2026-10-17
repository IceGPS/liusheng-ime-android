package com.icegps.voiceime.ime.pinyin

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView

/**
 * 手绘 QWERTY 拼音键盘（布局仿搜狗输入法）。
 *
 * 布局（四行）：
 * - 第 0 行：Q W E R T Y U I O P（上标数字 1～0）
 * - 第 1 行：A S D F G H J K L（上标符号 ～ ! @ # % " " * ?，居中）
 * - 第 2 行：Shift(↑) Z X C V B N M 退格(⌫)（字母上标 ( ) - _ : ; `）
 * - 第 3 行：符 | 123 | , | 空格(麦克风图标) | 。 | 中/英 | 回车(⏎)
 *
 * 交互：
 * - 点按字母键：输入字母；按住字母键上滑约半个键高：输入上标的数字/符号并弹预览框
 * - 退格键：点按删一个，长按连续删除
 * - 空格中央麦克风图标：点按切回语音键行
 * - 跟随系统深浅色
 */
class PinyinKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 按键事件回调（由 PinyinInputController 实现） */
    interface Listener {
        /** 字母键（含 Shift 后的大小写；标点键也走这里：',' / '。'） */
        fun onLetter(ch: Char)

        /** 上滑手势输入的上标数字/符号 */
        fun onSymbol(sym: Char)

        fun onBackspace()
        fun onEnter()

        /** Shift 键点按（视觉状态由本 View 内部维护） */
        fun onShift()

        /** 中/英切换 */
        fun onLangToggle()

        /** “符”键（第一阶段未实现） */
        fun onSymbolPage()

        /** “123”键（第一阶段未实现） */
        fun onDigitPage()

        /** 空格键 */
        fun onSpace()

        /** ⌨️ 键：切回极简语音键盘 */
        fun onVoiceMode()

        /** 空格中央麦克风图标：按住开始录音 */
        fun onVoiceRecordStart()

        /** 录音结束（松开手指 / 手势取消） */
        fun onVoiceRecordStop()
    }

    var listener: Listener? = null

    /** 中文模式（影响“中/英”键显示；输入路由由 Controller 决定） */
    var isChineseMode: Boolean = true
        set(v) {
            field = v
            invalidate()
        }

    // ---------- 按键模型 ----------

    private enum class Action {
        LETTER, SHIFT, BACKSPACE, SYMBOL_PAGE, DIGIT_PAGE,
        COMMA, SPACE, PERIOD, LANG, ENTER, SWITCH_VOICE
    }

    private class Key(
        val label: String,
        val hint: String?,
        val action: Action,
        val weight: Float,
        val isFunc: Boolean,
    ) {
        val rect = RectF()
    }

    // 第 0 行：Q～P，上标数字 1～0
    private val row0: List<Key> = listOf(
        "Q" to "1", "W" to "2", "E" to "3", "R" to "4", "T" to "5",
        "Y" to "6", "U" to "7", "I" to "8", "O" to "9", "P" to "0",
    ).map { (l, h) -> Key(l, h, Action.LETTER, 1f, false) }

    // 第 1 行：A～L，上标符号
    private val row1: List<Key> = listOf(
        "A" to "~", "S" to "!", "D" to "@", "F" to "#", "G" to "%",
        "H" to "“", "J" to "”", "K" to "*", "L" to "?",
    ).map { (l, h) -> Key(l, h, Action.LETTER, 1f, false) }

    // 第 2 行：Shift + Z～M + 退格
    private val row2: List<Key> = buildList {
        add(Key("↑", null, Action.SHIFT, 1.5f, true))
        add(Key("Z", "(", Action.LETTER, 1f, false))
        add(Key("X", ")", Action.LETTER, 1f, false))
        add(Key("C", "-", Action.LETTER, 1f, false))
        add(Key("V", "_", Action.LETTER, 1f, false))
        add(Key("B", ":", Action.LETTER, 1f, false))
        add(Key("N", ";", Action.LETTER, 1f, false))
        add(Key("M", "`", Action.LETTER, 1f, false))
        add(Key("⌫", null, Action.BACKSPACE, 1.5f, true))
    }

    // 第 3 行：底部功能行（权重按搜狗截图比例）
    private val row3: List<Key> = buildList {
        add(Key("符", null, Action.SYMBOL_PAGE, 1.3f, true))
        add(Key("123", null, Action.DIGIT_PAGE, 1.3f, true))
        add(Key(",", null, Action.COMMA, 0.9f, false))
        add(Key("⌨️", null, Action.SWITCH_VOICE, 1.3f, true)) // 切回极简语音键盘
        add(Key("", null, Action.SPACE, 3.0f, false)) // 空格：中央麦克风图标按住录音
        add(Key("。", null, Action.PERIOD, 0.9f, false))
        add(Key("中/英", null, Action.LANG, 1.3f, true))
        add(Key("⏎", null, Action.ENTER, 1.5f, true))
    }

    private val allRows = listOf(row0, row1, row2, row3)

    // ---------- 配色（跟随系统深浅色，与 VoiceImeService 一致） ----------

    private val isDark = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val keyBgColor = if (isDark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
    private val funcBgColor = if (isDark) 0xFF3A3A3C.toInt() else 0xFFE3E3E8.toInt()
    private val keyTextColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
    private val hintColor = 0xFF8E8E93.toInt()
    private val pressedColor = if (isDark) 0xFF48484A.toInt() else 0xFFD1D1D6.toInt()
    private val accentColor = 0xFF0A84FF.toInt()

    // ---------- 画笔 ----------

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = keyTextColor
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = hintColor
    }
    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        alpha = 170
    }

    // ---------- 尺寸 ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

    private var keyH = 0f
    private var rowGap = 0f
    private val cornerR: Float get() = dp(6).toFloat()

    /** 空格上麦克风图标的可点热区（绘制时更新） */
    private val micHotRect = RectF()

    /** 是否正在按住空格麦克风录音（用于图标变色 + 手势状态） */
    private var voiceRecording = false

    /**
     * 动态计算麦克风热区是否包含触摸点。
     * 不依赖 drawMicIcon 更新的缓存 micHotRect，直接从空格键矩形实时计算，
     * 避免折叠屏展开/折叠等尺寸变化时缓存不同步导致热区失效。
     */
    private fun isInMicHotZone(x: Float, y: Float): Boolean {
        val spaceKey = row3.find { it.action == Action.SPACE } ?: return false
        val s = dp(56).toFloat()
        val cx = spaceKey.rect.centerX()
        val cy = spaceKey.rect.centerY()
        return x >= cx - s / 2 && x <= cx + s / 2 &&
               y >= cy - s / 2 && y <= cy + s / 2
    }

    init {
        // 键盘不需要焦点；触摸完全自己处理
        isFocusable = false
        isFocusableInTouchMode = false
        setPadding(dp(4), dp(4), dp(4), dp(8))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        rowGap = dp(8).toFloat()
        val desiredH = (paddingTop + paddingBottom + dp(54) * 4 + rowGap * 3).toInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredH, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 键高按实际高度均分，保证 EXACTLY 模式下不变形
        keyH = (h - paddingTop - paddingBottom - rowGap * 3) / 4f
        layoutKeys()
        // 折叠屏展开/折叠时尺寸变化，强制重绘以更新 micHotRect 等绘制期缓存
        invalidate()
    }

    /** 计算每行每个键的矩形 */
    private fun layoutKeys() {
        val gap = dp(6).toFloat()
        val left = paddingLeft.toFloat()
        val totalW = (width - paddingLeft - paddingRight).toFloat()
        var top = paddingTop.toFloat()

        // 第 0 行：10 等宽键
        layoutWeightedRow(row0, left, top, totalW, gap)
        top += keyH + rowGap

        // 第 1 行：9 键，按第 0 行键宽居中
        val unit0 = (totalW - gap * 9) / 10f
        val row1W = unit0 * 9 + gap * 8
        layoutFixedRow(row1, left + (totalW - row1W) / 2, top, unit0, gap)
        top += keyH + rowGap

        // 第 2 行：Shift(1.5) + 7 字母 + 退格(1.5)，总权重 10
        layoutWeightedRow(row2, left, top, totalW, gap)
        top += keyH + rowGap

        // 第 3 行：底部功能行
        layoutWeightedRow(row3, left, top, totalW, gap)
    }

    private fun layoutWeightedRow(keys: List<Key>, left: Float, top: Float, totalW: Float, gap: Float) {
        val totalWeight = keys.sumOf { it.weight.toDouble() }.toFloat()
        val unit = (totalW - gap * (keys.size - 1)) / totalWeight
        var x = left
        for (k in keys) {
            val kw = unit * k.weight
            k.rect.set(x, top, x + kw, top + keyH)
            x += kw + gap
        }
    }

    private fun layoutFixedRow(keys: List<Key>, left: Float, top: Float, keyW: Float, gap: Float) {
        var x = left
        for (k in keys) {
            k.rect.set(x, top, x + keyW, top + keyH)
            x += keyW + gap
        }
    }

    // ---------- 绘制 ----------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (row in allRows) {
            for (k in row) drawKey(canvas, k)
        }
    }

    private fun drawKey(canvas: Canvas, k: Key) {
        val r = k.rect
        if (r.isEmpty) return

        // 背景
        bgPaint.color = when {
            // 录音中：整个空格键显示红色（与极简键盘空格键一致，手指遮挡也能看到）
            k.action == Action.SPACE && voiceRecording -> 0xFFE53935.toInt()
            k === pressedKey -> pressedColor
            k.action == Action.SHIFT && (shifted || capsLock) -> accentColor
            k.isFunc -> funcBgColor
            else -> keyBgColor
        }
        canvas.drawRoundRect(r, cornerR, cornerR, bgPaint)

        // 上标 hint（小字，置顶）
        if (k.hint != null) {
            hintPaint.textSize = sp(11f)
            canvas.drawText(k.hint, r.centerX(), r.top + sp(14f), hintPaint)
        }

        // 主标签
        when (k.action) {
            Action.SPACE -> drawMicIcon(canvas, r)
            Action.LANG -> drawLangLabel(canvas, r)
            Action.SHIFT -> {
                labelPaint.textSize = sp(20f)
                labelPaint.color =
                    if (shifted || capsLock) 0xFFFFFFFF.toInt() else keyTextColor
                canvas.drawText("↑", r.centerX(), textBaseline(r, labelPaint), labelPaint)
                labelPaint.color = keyTextColor
            }
            else -> {
                labelPaint.textSize = if (k.isFunc) sp(17f) else sp(22f)
                labelPaint.color = keyTextColor
                // 有上标时主标签略下移，避免重叠
                val cy = if (k.hint != null) r.centerY() + dp(5) else r.centerY()
                canvas.drawText(
                    k.label, r.centerX(),
                    cy - (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2,
                    labelPaint,
                )
            }
        }
    }

    /** 空格中央的麦克风图标（emoji），同时更新可点热区 */
    private fun drawMicIcon(canvas: Canvas, r: RectF) {
        // 录音中的红色反馈已由 drawKey 把整个空格键染红，这里只画图标
        emojiPaint.textSize = sp(22f)
        canvas.drawText("🎤", r.centerX(), textBaseline(r, emojiPaint), emojiPaint)
        val s = dp(56).toFloat()
        micHotRect.set(
            r.centerX() - s / 2, r.centerY() - s / 2,
            r.centerX() + s / 2, r.centerY() + s / 2,
        )
    }

    /** “中/英”：当前语言用 accent 色高亮 */
    private fun drawLangLabel(canvas: Canvas, r: RectF) {
        labelPaint.textSize = sp(17f)
        val wZhong = labelPaint.measureText("中")
        val wMid = labelPaint.measureText("/")
        val wYing = labelPaint.measureText("英")
        val total = wZhong + wMid + wYing
        var sx = r.centerX() - total / 2
        val base = textBaseline(r, labelPaint)
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = if (isChineseMode) accentColor else keyTextColor
        canvas.drawText("中", sx, base, labelPaint)
        sx += wZhong
        labelPaint.color = hintColor
        canvas.drawText("/", sx, base, labelPaint)
        sx += wMid
        labelPaint.color = if (!isChineseMode) accentColor else keyTextColor
        canvas.drawText("英", sx, base, labelPaint)
        // 恢复画笔状态
        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.color = keyTextColor
    }

    private fun textBaseline(r: RectF, p: Paint): Float {
        val fm = p.fontMetrics
        return r.centerY() - (fm.ascent + fm.descent) / 2
    }

    // ---------- 触摸与手势 ----------

    private var pressedKey: Key? = null
    private var downX = 0f
    private var downY = 0f

    /** 是否已进入“上滑输符号”模式 */
    private var swipeArmed = false

    /** Shift 状态：单击一次大写（下次按键后恢复），双击大写锁定 */
    private var shifted = false
    private var capsLock = false

    private val handler = Handler(Looper.getMainLooper())
    private var backspaceRepeat: Runnable? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val k = findKey(x, y) ?: return false
                pressedKey = k
                downX = x
                downY = y
                swipeArmed = false
                invalidate()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (k.action == Action.BACKSPACE) {
                    // 点按立即删一个，长按连续删
                    listener?.onBackspace()
                    startBackspaceRepeat()
                } else if (k.action == Action.SPACE && isInMicHotZone(x, y)) {
                    // 按住空格中央的麦克风图标：开始语音录音，松开结束
                    // （手指滑出热区仍继续录，直到 UP/CANCEL）
                    // 热区动态计算，不依赖绘制缓存，折叠屏尺寸变化也有效
                    voiceRecording = true
                    listener?.onVoiceRecordStart()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val k = pressedKey ?: return true
                // 上滑检测：仅字母键且有上标；阈值约半个键高
                if (!swipeArmed && k.action == Action.LETTER && k.hint != null) {
                    if (downY - y > keyH * 0.45f) {
                        swipeArmed = true
                        showPreview(k.hint, k.rect)
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val k = pressedKey
                stopBackspaceRepeat()
                dismissPreview()
                if (voiceRecording) {
                    // 按住录音结束：不走点按逻辑，直接停止转写
                    voiceRecording = false
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    listener?.onVoiceRecordStop()
                } else if (k != null) {
                    if (swipeArmed && k.hint != null) {
                        listener?.onSymbol(k.hint[0])
                    } else {
                        handleTap(k, x, y)
                    }
                }
                pressedKey = null
                swipeArmed = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopBackspaceRepeat()
                dismissPreview()
                if (voiceRecording) {
                    // 手势被系统取消：同样停止录音
                    voiceRecording = false
                    listener?.onVoiceRecordStop()
                }
                pressedKey = null
                swipeArmed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(k: Key, x: Float, y: Float) {
        when (k.action) {
            Action.LETTER -> {
                var ch = k.label[0]
                if (!shifted && !capsLock) ch = ch.lowercaseChar()
                listener?.onLetter(ch)
                // 单次大写：用过一次后恢复
                if (shifted && !capsLock) {
                    shifted = false
                    invalidate()
                }
            }
            Action.SHIFT -> {
                // 单击：小写→单次大写→大写锁定→小写，循环
                when {
                    capsLock -> {
                        capsLock = false
                        shifted = false
                    }
                    shifted -> {
                        shifted = false
                        capsLock = true
                    }
                    else -> shifted = true
                }
                listener?.onShift()
                invalidate()
            }
            Action.BACKSPACE -> {
                // DOWN 时已处理单击删除，这里无需重复
            }
            Action.ENTER -> listener?.onEnter()
            Action.SPACE -> {
                // 普通空格点按（麦克风热区的按住录音已在 ACTION_DOWN 处理，不会走到这里）
                listener?.onSpace()
            }
            Action.SWITCH_VOICE -> listener?.onVoiceMode()
            Action.LANG -> listener?.onLangToggle()
            Action.COMMA -> listener?.onLetter(',')
            Action.PERIOD -> listener?.onLetter('。')
            Action.SYMBOL_PAGE -> listener?.onSymbolPage()
            Action.DIGIT_PAGE -> listener?.onDigitPage()
        }
    }

    private fun findKey(x: Float, y: Float): Key? {
        for (row in allRows) {
            for (k in row) {
                if (k.rect.contains(x, y)) return k
            }
        }
        return null
    }

    // ---------- 退格长按连续删除 ----------

    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        val r = object : Runnable {
            override fun run() {
                listener?.onBackspace()
                handler.postDelayed(this, 60)
            }
        }
        backspaceRepeat = r
        handler.postDelayed(r, 400)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeat?.let { handler.removeCallbacks(it) }
        backspaceRepeat = null
    }

    // ---------- 按键预览弹窗 ----------

    private var previewPopup: PopupWindow? = null

    /** 在按键上方弹出预览框（上滑输入符号/数字时） */
    private fun showPreview(text: String, anchor: RectF) {
        dismissPreview()
        try {
            val tv = TextView(context).apply {
                this.text = text
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(keyTextColor)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    setColor(if (isDark) 0xFF3A3A3C.toInt() else 0xFFFFFFFF.toInt())
                    setStroke(dp(1), hintColor)
                }
                val p = dp(6)
                setPadding(dp(18), p, dp(18), p)
            }
            val w = dp(72)
            val h = dp(88)
            previewPopup = PopupWindow(tv, w, h, false).apply {
                // 允许超出键盘边界显示
                isClippingEnabled = false
            }
            val loc = IntArray(2)
            getLocationInWindow(loc)
            val x = (loc[0] + anchor.centerX() - w / 2).toInt()
            val y = (loc[1] + anchor.top - h - dp(8)).toInt()
            previewPopup?.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
        } catch (_: Exception) {
            // 预览失败不影响输入
        }
    }

    private fun dismissPreview() {
        try {
            previewPopup?.dismiss()
        } catch (_: Exception) {
        }
        previewPopup = null
    }

    override fun onDetachedFromWindow() {
        stopBackspaceRepeat()
        dismissPreview()
        super.onDetachedFromWindow()
    }
}
