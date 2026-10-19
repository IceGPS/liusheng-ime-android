package com.icegps.voiceime.ime.pinyin

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
* 符号 / 数字键盘（V63）。
*
* 替换式全键盘：拼音键盘点"符"/"123"进入。点选符号后**停留**（可连打，
* 不自动跳回字母键盘）；ABC 返回字母键盘。
*
* 空格键与极简键盘、拼音键盘行为一致：短按=空格，长按 100ms=语音输入
* （录音时整键变红），松开转写上屏。
*/
class SymbolKeyboardView @JvmOverloads constructor(
context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
): View(context, attrs, defStyleAttr) {

enum class Mode { SYMBOL, DIGIT}

interface Listener {
/** 点选符号/数字键：直接上屏（停留，不跳回） */
fun onSymbolKey(sym: String)
fun onBackspace()
fun onEnter()
fun onSpace()
/** ABC：返回字母键盘 */
fun onBackToLetters()
/** 符号键盘 → 数字键盘 */
fun onGotoDigit()
/** 数字键盘 → 符号键盘 */
fun onGotoSymbol()
/** 空格键长按开始录音（与极简/拼音键盘一致） */
fun onVoiceRecordStart()
/** 松开结束录音转写 */
fun onVoiceRecordStop()
}

var listener: Listener? = null

var mode: Mode = Mode.SYMBOL
private set
/** 符号页：0=中文标点，1=英文符号 */
var symbolPage: Int = 0
private set

fun showSymbol() {
mode = Mode.SYMBOL
symbolPage = 0
requestLayout()
invalidate()
}

fun showDigit() {
mode = Mode.DIGIT
requestLayout()
invalidate()
}

// ---------- 按键模型 ----------

private enum class Action {
SYMBOL, BACKSPACE, ENTER, SPACE, ABC, PAGE, GOTO_DIGIT, GOTO_SYMBOL,
}

private data class Key(
val label: String,
val weight: Float,
val action: Action,
val isFunc: Boolean = false,
val rect: RectF = RectF(),
)

/** 中文标点页（全角） */
private val symbolPageZh = listOf(
listOf("，", "。", "；", "：", "？", "！"),
listOf("、", "…", "—", "·", "《", "》"),
listOf("「", "」", "『", "』", "（", "）"),
listOf("", "￥", "＂", "＇", "～"),
)

/** 英文符号页（半角） */
private val symbolPageEn = listOf(
listOf(",", ".", ";", ":", "?", "!", "'"),
listOf("\"", "-", "_", "(", ")", "[", "]"),
listOf("{", "}", "@", "#", "$", "%", "&"),
listOf("*", "+", "=", "/", "\\", "|", "~"),
)

/** 数字页 */
private val digitRows = listOf(
listOf("1", "2", "3", "+", "-"),
listOf("4", "5", "6", "×", "÷"),
listOf("7", "8", "9", "=", "%"),
listOf(".", "0", "(", ")", ","),
)

/** 当前网格行（不含底部功能行） */
private fun gridRows(): List<List<String>> = when (mode) {
Mode.SYMBOL -> if (symbolPage == 0) symbolPageZh else symbolPageEn
Mode.DIGIT -> digitRows
}

/** 底部功能行 */
private fun bottomRow(): List<Key> = when (mode) {
Mode.SYMBOL -> listOf(
Key("ABC", 1.2f, Action.ABC, true),
Key(if (symbolPage == 0) "1/2" else "2/2", 1.0f, Action.PAGE, true),
Key("123", 1.0f, Action.GOTO_DIGIT, true),
Key("空格", 2.5f, Action.SPACE, true),
Key("⌫", 1.2f, Action.BACKSPACE, true),
Key("⏎", 1.4f, Action.ENTER, true),
)
Mode.DIGIT -> listOf(
Key("ABC", 1.2f, Action.ABC, true),
Key("符", 1.0f, Action.GOTO_SYMBOL, true),
Key("空格", 3.5f, Action.SPACE, true),
Key("⌫", 1.2f, Action.BACKSPACE, true),
Key("⏎", 1.4f, Action.ENTER, true),
)
}

// ---------- 配色（与 PinyinKeyboardView 一致，跟随系统深浅色） ----------

private val isDark = (resources.configuration.uiMode and
Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private val keyBgColor = if (isDark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
private val funcBgColor = if (isDark) 0xFF3A3A3C.toInt() else 0xFFE3E3E8.toInt()
private val keyTextColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
private val pressedColor = if (isDark) 0xFF48484A.toInt() else 0xFFD1D1D6.toInt()

private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
textAlign = Paint.Align.CENTER
color = keyTextColor
}

/** emoji 画笔（空格键 🎤，与拼音键盘一致） */
private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
textAlign = Paint.Align.CENTER
alpha = 170
}

private fun textBaseline(r: RectF, p: Paint): Float {
val fm = p.fontMetrics
return r.centerY() - (fm.ascent + fm.descent) / 2
}

// ---------- 尺寸 ----------

private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity

private val rowH = 54
private val rowGap = 6
private val sidePad = 4
private val cornerR: Float get() = dp(6).toFloat()

private var allKeys: List<Key> = emptyList()

// ---------- 空格键语音（与拼音键盘一致：短按=空格，长按 100ms=语音） ----------

private var spaceTapValid = false
private var voiceRecording = false
private val handler = Handler(Looper.getMainLooper())
private val spaceLongPressRunnable = Runnable {
if (spaceTapValid &&!voiceRecording) {
spaceTapValid = false
voiceRecording = true
performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
listener?.onVoiceRecordStart()
invalidate()
}
}

init {
isFocusable = false
isFocusableInTouchMode = false
}

override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
val w = MeasureSpec.getSize(widthMeasureSpec)
// 4 行网格 + 1 行功能行
val h = dp(rowH) * 5 + dp(rowGap) * 4 + dp(sidePad) * 2
setMeasuredDimension(w, h)
layoutKeys(w, h)
}

private fun layoutKeys(w: Int, h: Int) {
val keys = mutableListOf<Key>()
val rows = gridRows().map { row -> row.map { Key(it, 1f, Action.SYMBOL)}} + listOf(bottomRow())
var y = dp(sidePad).toFloat()
val contentW = w - dp(sidePad) * 2
for (row in rows) {
val totalW = row.sumOf { it.weight.toDouble()}.toFloat()
var x = dp(sidePad).toFloat()
for (k in row) {
val kw = contentW * k.weight / totalW
// 键之间留 3dp 间隙
k.rect.set(x + dp(3) / 2f, y, x + kw - dp(3) / 2f, y + dp(rowH))
x += kw
keys.add(k)
}
y += dp(rowH) + dp(rowGap)
}
allKeys = keys
}

// ---------- 绘制 ----------

override fun onDraw(canvas: Canvas) {
for (k in allKeys) {
val r = k.rect
if (r.isEmpty) continue
bgPaint.color = when {
// 录音中：整个空格键显示红色（优先于按下态，与拼音键盘一致，
// 否则手指按住时 pressedKey 的按灰会盖住红色）
k.action == Action.SPACE && voiceRecording -> 0xFFE53935.toInt()
k === pressedKey -> pressedColor
k.isFunc -> funcBgColor
else -> keyBgColor
}
canvas.drawRoundRect(r, cornerR, cornerR, bgPaint)
if (k.action == Action.SPACE) {
// 空格键画 🎤（与拼音键盘一致：长按=语音的视觉提示）
emojiPaint.textSize = sp(22f)
canvas.drawText("🎤", r.centerX(), textBaseline(r, emojiPaint), emojiPaint)
} else {
labelPaint.textSize = if (k.isFunc) sp(16f) else sp(22f)
labelPaint.color = keyTextColor
val fm = labelPaint.fontMetrics
canvas.drawText(k.label, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, labelPaint)
}
}
}

// ---------- 退格长按连续删除（与拼音/极简键盘一致） ----------

private var backspaceRepeat: Runnable? = null

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

// ---------- 触摸 ----------

private var pressedKey: Key? = null
private var downX = 0f
private var downY = 0f

private fun findKey(x: Float, y: Float): Key? =
allKeys.firstOrNull { it.rect.contains(x, y)}

override fun onTouchEvent(event: MotionEvent): Boolean {
val x = event.x
val y = event.y
when (event.actionMasked) {
MotionEvent.ACTION_DOWN -> {
val k = findKey(x, y)?: return false
pressedKey = k
downX = x
downY = y
invalidate()
performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
if (k.action == Action.BACKSPACE) {
// 点按立即删一个，长按 400ms 后连续删（与拼音/极简键盘一致）
listener?.onBackspace()
startBackspaceRepeat()
} else if (k.action == Action.SPACE) {
// 空格键：短按=空格，长按 100ms=语音输入
spaceTapValid = true
handler.postDelayed(spaceLongPressRunnable, 100L)
}
return true
}
MotionEvent.ACTION_MOVE -> {
// V65：只在手指滑出空格键才取消长按。之前"相对落点漂移 10dp 就取消"
// 太严格——长按时手指自然漂移/按压力变化会导致 centroid 漂移，
// 在某些设备上 100ms 内就触发取消，表现为"长按永远不变红"。
// 空格键很宽，键内漂移是正常的，不应取消。
val k = pressedKey
if (spaceTapValid && !voiceRecording && k?.action == Action.SPACE) {
val r = k.rect
val slop = dp(8).toFloat()
if (x < r.left - slop || x > r.right + slop ||
y < r.top - slop || y > r.bottom + slop) {
spaceTapValid = false
handler.removeCallbacks(spaceLongPressRunnable)
}
}
return true
}
MotionEvent.ACTION_UP -> {
handler.removeCallbacks(spaceLongPressRunnable)
stopBackspaceRepeat()
if (voiceRecording) {
// 长按录音结束：直接停止转写，不走点按逻辑
voiceRecording = false
performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
listener?.onVoiceRecordStop()
} else {
spaceTapValid = false
val k = pressedKey
val up = findKey(x, y)
// 退格已在 DOWN 时处理（立即删一个+长按连删），UP 不再重复触发
if (k!= null && up === k && k.action != Action.BACKSPACE) fireKey(k)
}
pressedKey = null
invalidate()
return true
}
MotionEvent.ACTION_CANCEL -> {
handler.removeCallbacks(spaceLongPressRunnable)
stopBackspaceRepeat()
spaceTapValid = false
if (voiceRecording) {
voiceRecording = false
listener?.onVoiceRecordStop()
}
pressedKey = null
invalidate()
return true
}
}
return super.onTouchEvent(event)
}

private fun fireKey(k: Key) {
when (k.action) {
Action.SYMBOL -> listener?.onSymbolKey(k.label)
// BACKSPACE 不走这里：已在 DOWN 时处理（立即删一个+长按连删）
Action.ENTER -> listener?.onEnter()
Action.SPACE -> listener?.onSpace()
Action.ABC -> listener?.onBackToLetters()
Action.PAGE -> {
symbolPage = 1 - symbolPage
requestLayout()
invalidate()
}
Action.GOTO_DIGIT -> listener?.onGotoDigit()
Action.GOTO_SYMBOL -> listener?.onGotoSymbol()
}
}
}
