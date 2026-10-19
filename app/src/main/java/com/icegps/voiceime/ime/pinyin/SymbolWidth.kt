package com.icegps.voiceime.ime.pinyin

/**
 * 中/英文输入状态的符号全角半角映射（V58）。
 *
 * 规则：中文状态 → 全角；英文状态 → 半角。
 * 需要区分的只是常用标点（句号/逗号/分号/问号/百分号/感叹号/括号/
 * 货币符号/引号/冒号）；技术符号（@ # * _ - + = / \ 等）在两种状态下
 * 都用半角——邮箱、密码、代码里全角会出错，所以不在映射表里。
 */
object SymbolWidth {

    /** 半角 → 全角（中文状态）。不在表中的符号不区分，保持半角。 */
    private val halfToFull = mapOf(
        ',' to '，',
        '.' to '。',
        ';' to '；',
        '?' to '？',
        '%' to '％',
        '!' to '！',
        '(' to '（',
        ')' to '）',
        '$' to '￥',
        ':' to '：',
    )

    /** 单个符号按中英文状态取显示/输入字符 */
    fun of(ch: Char, chinese: Boolean): Char =
        if (chinese) halfToFull[ch] ?: ch else ch

    /** 双引号：中文输出成对前后引号 “”，英文输出直引号 " */
    fun quote(chinese: Boolean): String = if (chinese) "\u201C\u201D" else "\""

    /** 单引号：中文输出成对单引号 ‘’，英文输出直单引号 ' */
    fun singleQuote(chinese: Boolean): String = if (chinese) "\u2018\u2019" else "'"
}
