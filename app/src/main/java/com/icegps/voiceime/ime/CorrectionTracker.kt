package com.icegps.voiceime.ime

/**
 * 语音纠错捕获（一期：只记录，不改变任何输入行为）。
 *
 * 机制：检查点快照差分。语音上屏时登记待观察片段（整段语音文本在编辑器
 * 全文快照中的位置）；在下一次语音上屏前 / 输入会话结束时，把编辑器当前
 * 全文与快照做字符级差分，从中提取"落在片段内的小幅替换"作为纠错事件。
 * 设计见 goals/voice-input-method-project/files/correction-learning-phase1-design.md。
 *
 * 纯逻辑、无 Android 依赖：全文读取与事件落盘由 VoiceImeService 胶水层负责。
 * 任何异常输入（读不到全文、差分规模超限）都静默放弃，绝不影响输入。
 */
class CorrectionTracker {

    /** 一条纠错事件（同音判定由胶水层用 CharPinyinTable 补齐后落盘）。 */
    data class Correction(
        val voiceId: String,
        val before: String,
        val after: String,
        val ctxL: String,
        val ctxR: String,
    )

    private var snapshot: String? = null
    private var segStart: Int = -1
    private var segEnd: Int = -1
    private var voiceId: String? = null

    val hasPending: Boolean get() = snapshot != null

    /**
     * 登记一段刚上屏的语音文本。
     * @param fullTextAfter 上屏后读回的编辑器全文
     * @param segStartInAfter 片段在全文中的起始偏移（= 上屏前选区起点）
     * @param segmentText 片段文本本身（用于回读校验，对不上则不登记）
     */
    fun onVoiceCommitted(id: String, fullTextAfter: String, segStartInAfter: Int, segmentText: String) {
        clear()
        val start = segStartInAfter.coerceIn(0, fullTextAfter.length)
        val end = start + segmentText.length
        if (segmentText.isEmpty() || end > fullTextAfter.length) return
        if (fullTextAfter.substring(start, end) != segmentText) return
        snapshot = fullTextAfter
        segStart = start
        segEnd = end
        voiceId = id
    }

    /**
     * 结算：把当前全文与快照差分，产出纠错事件并清空状态。
     * @param currentFullText 当前编辑器全文；null = 读不到，放弃结算。
     */
    fun flush(currentFullText: String?): List<Correction> {
        val snap = snapshot
        val id = voiceId
        val s0 = segStart
        val s1 = segEnd
        clear()
        if (snap == null || id == null || currentFullText == null) return emptyList()
        if (currentFullText == snap) return emptyList()
        val hunks = diffHunks(snap, currentFullText) ?: return emptyList()
        // 只看与片段跨度（快照坐标）相交的 hunk
        val inSeg = hunks.filter { it.aStart < s1 && it.aStart + it.aText.length > s0 }
        // 重写守门：片段内编辑总量超过片段长度一半，视为整句改写而非纠错，
        // 全部放弃——否则最小差分会把改写拆成一堆小替换噪声事件。
        val segLen = s1 - s0
        val edited = inSeg.sumOf { maxOf(it.aText.length, it.bText.length) }
        if (edited * 2 > segLen) return emptyList()
        val out = ArrayList<Correction>()
        for (h in inSeg) {
            // 必须是替换（删+紧邻插）
            if (h.aText.isEmpty() || h.bText.isEmpty()) continue
            if (h.aText.length > MAX_SIDE || h.bText.length > MAX_SIDE) continue
            out.add(
                Correction(
                    voiceId = id,
                    before = h.aText,
                    after = h.bText,
                    ctxL = currentFullText.substring(maxOf(0, h.bStart - CTX_LEN), h.bStart),
                    ctxR = currentFullText.substring(
                        minOf(currentFullText.length, h.bStart + h.bText.length),
                        minOf(currentFullText.length, h.bStart + h.bText.length + CTX_LEN),
                    ),
                )
            )
        }
        return out
    }

    fun clear() {
        snapshot = null
        segStart = -1
        segEnd = -1
        voiceId = null
    }

    private data class Hunk(val aStart: Int, val aText: String, val bStart: Int, val bText: String)

    /**
     * 字符级差分：公共前后缀裁剪 + 中间段 LCS DP 对齐，产出编辑 hunk 列表
     * （快照坐标 aStart / 当前坐标 bStart）。中间段规模超限返回 null（放弃）。
     */
    private fun diffHunks(a: String, b: String): List<Hunk>? {
        var prefix = 0
        val maxPrefix = minOf(a.length, b.length)
        while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(a.length, b.length) - prefix &&
            a[a.length - 1 - suffix] == b[b.length - 1 - suffix]
        ) suffix++
        val aMid = a.substring(prefix, a.length - suffix)
        val bMid = b.substring(prefix, b.length - suffix)
        if (aMid.isEmpty() && bMid.isEmpty()) return emptyList()
        if (aMid.isEmpty() || bMid.isEmpty()) {
            // 纯插入或纯删除：单个 hunk（后续按替换规则过滤掉）
            return listOf(Hunk(prefix, aMid, prefix, bMid))
        }
        if (aMid.length.toLong() * bMid.length.toLong() > MAX_DP_CELLS) return null

        // LCS DP（(n+1) x (m+1)）
        val n = aMid.length
        val m = bMid.length
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (aMid[i] == bMid[j]) dp[i + 1][j + 1] + 1
                           else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        // 回溯成 ops，再把相邻的非等值 ops 合并为 hunk
        val hunks = ArrayList<Hunk>()
        var i = 0
        var j = 0
        var hunkAStart = -1
        var hunkBStart = -1
        val delBuf = StringBuilder()
        val insBuf = StringBuilder()
        fun closeHunk() {
            if (hunkAStart >= 0) {
                hunks.add(Hunk(prefix + hunkAStart, delBuf.toString(), prefix + hunkBStart, insBuf.toString()))
                hunkAStart = -1
                delBuf.setLength(0)
                insBuf.setLength(0)
            }
        }
        fun openHunk() {
            if (hunkAStart < 0) {
                hunkAStart = i
                hunkBStart = j
            }
        }
        while (i < n && j < m) {
            when {
                aMid[i] == bMid[j] -> { closeHunk(); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { openHunk(); delBuf.append(aMid[i]); i++ }
                else -> { openHunk(); insBuf.append(bMid[j]); j++ }
            }
        }
        while (i < n) { openHunk(); delBuf.append(aMid[i]); i++ }
        while (j < m) { openHunk(); insBuf.append(bMid[j]); j++ }
        closeHunk()
        return hunks
    }

    companion object {
        /** 替换两侧的最大长度（同音字纠错的典型粒度） */
        private const val MAX_SIDE = 4
        /** 上下文各取的字数 */
        private const val CTX_LEN = 6
        /** DP 规模上限（中间段长度乘积），超限放弃结算 */
        private const val MAX_DP_CELLS = 250_000L
    }
}
