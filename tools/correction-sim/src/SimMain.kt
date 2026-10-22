import com.icegps.voiceime.ime.CorrectionTracker
import com.icegps.voiceime.util.CharPinyinTable
import java.io.File

/**
 * CorrectionTracker + CharPinyinTable 的 JVM 验证（纯逻辑，无 Android 依赖）。
 * 场景对应设计文档：同音改 / 双改 / 删内容 / 改说法 / 片段外编辑 / 纯插入 /
 * 超长替换 / 无变化 / 连续两句的结算链。
 */

private var failures = 0

private fun check(name: String, cond: Boolean, detail: String = "") {
    println("  ${if (cond) "PASS" else "FAIL"} $name${if (detail.isEmpty()) "" else " — $detail"}")
    if (!cond) failures++
}

private fun trackerWith(voiceText: String, preText: String = "", voiceId: String = "v1"): CorrectionTracker {
    val t = CorrectionTracker()
    t.onVoiceCommitted(voiceId, preText + voiceText, preText.length, voiceText)
    return t
}

fun main() {
    val tsv = File(System.getProperty("charPinyinTsv")).readLines()
    val table = CharPinyinTable.fromTsv(tsv)

    println("=== Judge: homophone decisions ===")
    check("王工/汪工 homophone", table.judge("王工", "汪工").first)
    check("在/再 homophone", table.judge("在", "再").first)
    check("的/地 homophone", table.judge("的", "地").first)
    check("做/作 homophone", table.judge("做", "作").first)
    check("明天/后天 NOT homophone", !table.judge("明天", "后天").first)
    check("个/份 NOT homophone", !table.judge("个", "份").first)
    check("judge pinyin display 王工", table.judge("王工", "汪工").second == "wang gong")

    println("=== S1: single homophone fix inside segment (char-level minimal diff) ===")
    trackerWith("请联系王工确认一下").also { t ->
        val ev = t.flush("请联系汪工确认一下")
        check("one event", ev.size == 1, "$ev")
        check("before/after are the minimal char substitution", ev.firstOrNull()?.before == "王" && ev.firstOrNull()?.after == "汪")
        check("context carries the word", ev.firstOrNull()?.ctxR?.startsWith("工") == true)
        check("voiceId linked", ev.firstOrNull()?.voiceId == "v1")
    }

    println("=== S2: two fixes in one segment ===")
    trackerWith("请把这个文件给他看下").also { t ->
        val ev = t.flush("请把这份文件给他看完")
        check("two events", ev.size == 2, "$ev")
        check("pairs", ev.map { it.before to it.after }.toSet() == setOf("个" to "份", "下" to "完"))
    }

    println("=== S3: whole segment deleted (content removal) -> no event ===")
    trackerWith("这是一句要删掉的话", preText = "开头。").also { t ->
        val ev = t.flush("开头。")
        check("no events", ev.isEmpty(), "$ev")
    }

    println("=== S4: rephrase (non-homophone) still recorded, flagged by judge ===")
    trackerWith("我们明天去现场").also { t ->
        val ev = t.flush("我们后天去现场")
        check("one event", ev.size == 1, "$ev")
        check("judge says not homophone", ev.firstOrNull()?.let { !table.judge(it.before, it.after).first } == true)
    }

    println("=== S5: edit OUTSIDE the segment -> no event ===")
    trackerWith("讨论王工的方案", preText = "标题：今天开会。内容：").also { t ->
        val ev = t.flush("标题：今日开会。内容：讨论王工的方案")
        check("no events", ev.isEmpty(), "$ev")
    }

    println("=== S6: pure insertion inside segment -> no event ===")
    trackerWith("请联系王工确认").also { t ->
        val ev = t.flush("请尽快联系王工确认")
        check("no events", ev.isEmpty(), "$ev")
    }

    println("=== S7: rephrase decomposes into small NON-homophone events (stats-only bucket) ===")
    trackerWith("这个事情比较复杂").also { t ->
        val ev = t.flush("这件事人比较多啊")
        check("events exist but all non-homophone", ev.isNotEmpty() && ev.all { !table.judge(it.before, it.after).first }, "$ev")
    }

    println("=== S12: segment mostly replaced (>half edited) -> rewrite guard drops all ===")
    trackerWith("请把这个文件发给王工确认一下谢谢").also { t ->
        val ev = t.flush("明天上午九点开会讨论预算和人事安排问题")
        check("no events (rewrite guard)", ev.isEmpty(), "$ev")
    }

    println("=== S11: single 5-char substitution in a long segment -> size filter ===")
    trackerWith("编号是一二三四五请核对一下这个数据").also { t ->
        val ev = t.flush("编号是甲乙丙丁戊请核对一下这个数据")
        check("no events (side > 4)", ev.isEmpty(), "$ev")
    }

    println("=== S8: no change -> no event ===")
    trackerWith("一切正常").also { t ->
        check("no events", t.flush("一切正常").isEmpty())
    }

    println("=== S9: two voice commits — first flush sees only insertion, second captures its fix ===")
    CorrectionTracker().also { t ->
        t.onVoiceCommitted("v1", "第一句王工", 0, "第一句王工")
        val ev1 = t.flush("第一句王工第二句在吗") // simulates state right before committing sentence 2
        check("flush before 2nd commit: no events", ev1.isEmpty(), "$ev1")
        t.onVoiceCommitted("v2", "第一句王工第二句在吗", 5, "第二句在吗")
        val ev2 = t.flush("第一句王工第二句再吗")
        check("second segment fix captured", ev2.size == 1 && ev2[0].before == "在" && ev2[0].voiceId == "v2", "$ev2")
    }

    println("=== S10: unreadable editor at flush (null) -> no events, no crash ===")
    trackerWith("随便一句").also { t ->
        check("no events", t.flush(null).isEmpty())
        check("state cleared", !t.hasPending)
    }

    println()
    println(if (failures == 0) "ALL PASS" else "$failures FAILURES")
    if (failures > 0) kotlin.system.exitProcess(1)
}
