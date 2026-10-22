package com.icegps.voiceime.ime

import android.view.inputmethod.InputConnection

/**
 * 编辑器侧的通用 InputConnection 操作（各键盘共用）。
 */

/**
 * 删除一步（退格键的一次按下）：
 *
 * - 编辑器里有选中文字时：整段删除选区——这是所有主流输入法/编辑器的标准行为。
 * - 没有选区时：删除光标前 1 个字符。
 *
 * 为什么不能无条件用 deleteSurroundingText(1, 0)：按 InputConnection 契约，
 * 它删的是**选区两端之外**的文字（选区起点之前 / 终点之后），永远不删选区
 * 本身。有选区时调它，删掉的是选区左边的第一个字、选区原封不动
 * （2026-10-22 Hongjie 实机报告，仿真 tools/backspace-sim 场景 H 已复现）。
 * 删选区的标准做法是 commitText("", 1)（以空文本替换选区）。
 *
 * getSelectedText 在个别编辑器实现上可能抛异常或返回不准，失败时退回
 * 旧行为（删光标前一字），不让退格键失灵。
 */
fun InputConnection.deleteOneBackward() {
    val selected = try {
        getSelectedText(0)
    } catch (e: Exception) {
        null
    }
    if (!selected.isNullOrEmpty()) {
        commitText("", 1)
    } else {
        deleteSurroundingText(1, 0)
    }
}
