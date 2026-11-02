package com.ilya.dzshka.ui

import com.ilya.dzshka.data.*
import java.time.LocalDateTime

/** «Что сейчас»: общий расчёт для карточки в приложении и для виджета. */
object NowState {
    data class Info(
        val big: String,
        val small: String = "",
        val next: String = "",
        /** 0..1 — сколько прошло от урока/перемены; null — полоски нет */
        val prog: Float? = null,
        /** коротко для виджета в 1 клетку */
        val short1: String = big,
        val short2: String = "",
        /** одна строка для узкой полосы */
        val line: String = small,
        /** идёт учебный день: что-то меняется каждую минуту */
        val live: Boolean = false,
    )

    fun dur(min: Int): String = if (min >= 60) "${min / 60} ч ${min % 60} мин" else "$min мин"

    fun compute(d: AppData, now: LocalDateTime = LocalDateTime.now()): Info {
        val today = now.toLocalDate()
        val m = now.hour * 60 + now.minute
        val sec = now.second
        val lessons = Sched.lessonsOn(d, today).filter { it.start != null && it.end != null }
        fun name(l: Lesson) = d.subject(l.subjectId)?.let { listOfNotNull(it.emoji.takeIf { e -> e.isNotBlank() }, it.name).joinToString(" ") } ?: "?"
        val cur = lessons.firstOrNull { m >= it.start!! && m < it.end!! }
        val nextL = lessons.firstOrNull { it.start!! > m }
        return when {
            Sched.isHoliday(d, today) -> Info("Каникулы 🎉", "Уроков нет", short2 = "уроков нет")
            lessons.isEmpty() -> Info("Сегодня уроков нет", "Выходной", short1 = "Выходной", short2 = "уроков нет")
            cur != null -> Info(
                big = "Идёт ${cur.number} урок",
                small = "${name(cur)} · ${Time.fmt(cur.start!!)}–${Time.fmt(cur.end!!)} · осталось ${dur(cur.end!! - m)}",
                prog = ((m - cur.start!!) * 60 + sec) / ((cur.end!! - cur.start!!) * 60f),
                next = if (nextL != null) "Потом перемена ${dur(nextL.start!! - cur.end!!)}, дальше ${nextL.number} урок — ${name(nextL)}" else "Это последний урок 🏁",
                short1 = "${cur.number} урок",
                short2 = dur(cur.end!! - m),
                line = "${name(cur)} · осталось ${dur(cur.end!! - m)}",
                live = true,
            )
            nextL != null && lessons.first().start!! > m -> Info(
                big = "Уроки начнутся в ${Time.fmt(nextL.start!!)}",
                small = "Через ${dur(nextL.start!! - m)} · ${nextL.number} урок — ${name(nextL)}",
                short1 = "в ${Time.fmt(nextL.start!!)}",
                short2 = "через ${dur(nextL.start!! - m)}",
                line = "Через ${dur(nextL.start!! - m)} — ${name(nextL)}",
                live = true,
            )
            nextL != null -> {
                val prev = lessons.last { it.end!! <= m }
                Info(
                    big = "Перемена · до звонка ${dur(nextL.start!! - m)}",
                    small = "Дальше ${nextL.number} урок — ${name(nextL)} в ${Time.fmt(nextL.start!!)}",
                    prog = ((m - prev.end!!) * 60 + sec) / ((nextL.start!! - prev.end!!) * 60f),
                    next = "Перемена ${dur(nextL.start!! - prev.end!!)}",
                    short1 = "Перемена",
                    short2 = dur(nextL.start!! - m),
                    line = "Дальше ${nextL.number} урок — ${name(nextL)}",
                    live = true,
                )
            }
            else -> {
                val nd = Sched.nextSchoolDay(d, today)
                val first = Sched.lessonsOn(d, nd).firstOrNull { it.start != null }
                Info(
                    big = "Уроки закончились ✅",
                    small = if (first != null) "Следующий день: ${prettyDate(d, nd)}, ${first.number} урок в ${Time.fmt(first.start!!)} — ${name(first)}" else "",
                    short1 = "Всё ✅",
                    short2 = "уроки закончились",
                )
            }
        }
    }
}
