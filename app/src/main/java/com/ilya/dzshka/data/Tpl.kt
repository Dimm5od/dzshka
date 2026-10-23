package com.ilya.dzshka.data

import java.time.LocalDate
import java.time.LocalTime

/** Всё, из чего шаблон может взять значения. */
data class TplCtx(
    val subject: Subject? = null,
    val subjectName: String = "",
    val text: String = "",
    val noHw: Boolean = false,
    val due: LocalDate = LocalDate.now(),
    val lessonDate: LocalDate = LocalDate.now(),
    val lessonNum: Int? = null,
    val photoCount: Int = 0,
    val today: LocalDate = LocalDate.now(),
    val now: LocalTime = LocalTime.now(),
    val author: String = "",
)

object Tpl {

    fun render(tpl: String, d: AppData, c: TplCtx): String {
        val toks = d.tokens.filter { it.text.isNotEmpty() }.sortedByDescending { it.text.length }
        val sb = StringBuilder()
        var i = 0
        var prev: Char = ' ' // символ, который «стоит перед» текущей позицией (с учётом \n и т.п.)
        while (i < tpl.length) {
            val ch = tpl[i]
            if (ch == '\\' && i + 1 < tpl.length) {
                val n = tpl[i + 1]
                val out = when (n) { 'n' -> '\n'; 't' -> '\t'; else -> n }
                sb.append(out)
                prev = out
                i += 2
                continue
            }
            var matched = false
            for (t in toks) {
                val len = t.text.length
                if (!tpl.regionMatches(i, t.text, 0, len, ignoreCase = !d.tokenCase)) continue
                val end = i + len
                if (d.tokenWholeWord) {
                    if (t.text.first().isLetterOrDigit() && prev.isLetterOrDigit()) continue
                    if (t.text.last().isLetterOrDigit() && end < tpl.length && tpl[end].isLetterOrDigit()) continue
                }
                sb.append(value(t, d, c))
                prev = t.text.last()
                i = end
                matched = true
                break
            }
            if (!matched) {
                sb.append(ch)
                prev = ch
                i++
            }
        }
        return sb.toString()
    }

    fun textValue(d: AppData, c: TplCtx): String = when {
        c.noHw -> d.templates.noHw
        c.text.isBlank() && c.photoCount > 0 -> d.templates.emptyText
        else -> c.text.trim()
    }

    fun value(t: TokenDef, d: AppData, c: TplCtx): String {
        val date = when (t.source) {
            DateSource.DUE -> c.due
            DateSource.LESSON -> c.lessonDate
            DateSource.TODAY -> c.today
        }
        return when (t.type) {
            TokenType.DAY -> date.dayOfMonth.toString()
            TokenType.DAY2 -> "%02d".format(date.dayOfMonth)
            TokenType.MONTH_A -> d.monthsA.getOrElse(date.monthValue - 1) { "" }
            TokenType.MONTH_B -> d.monthsB.getOrElse(date.monthValue - 1) { "" }
            TokenType.MONTH_NUM -> date.monthValue.toString()
            TokenType.MONTH_NUM2 -> "%02d".format(date.monthValue)
            TokenType.YEAR -> date.year.toString()
            TokenType.YEAR2 -> "%02d".format(date.year % 100)
            TokenType.WEEKDAY -> d.weekdays.getOrElse(date.dayOfWeek.value - 1) { "" }
            TokenType.WEEKDAY_SHORT -> d.weekdaysShort.getOrElse(date.dayOfWeek.value - 1) { "" }
            TokenType.SUBJECT -> c.subject?.name?.takeIf { it.isNotBlank() } ?: c.subjectName
            TokenType.SUBJECT_SHORT -> c.subject?.short?.takeIf { it.isNotBlank() }
                ?: c.subject?.name?.takeIf { it.isNotBlank() } ?: c.subjectName
            TokenType.SUBJECT_EMOJI -> c.subject?.emoji ?: ""
            TokenType.TEXT -> textValue(d, c)
            TokenType.LESSON_NUM -> c.lessonNum?.toString() ?: ""
            TokenType.PHOTO_COUNT -> c.photoCount.toString()
            TokenType.TIME_NOW -> "%02d:%02d".format(c.now.hour, c.now.minute)
            TokenType.AUTHOR -> c.author
            TokenType.CUSTOM -> t.value
            TokenType.NEWLINE -> "\n"
        }
    }

    /** Убирает пробелы в конце строк и лишние пустые строки по краям (например, когда нет эмодзи). */
    fun tidy(s: String): String =
        s.lines().joinToString("\n") { it.trimEnd().replace(Regex("^ +"), "") }.trim('\n')
}
