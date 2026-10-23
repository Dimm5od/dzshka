package com.ilya.dzshka.data

import java.time.LocalDate
import java.time.LocalTime

/** Одно сообщение в канал. key — для докачки после обрыва, owners — записи ДЗ, к которым оно относится. */
sealed class Post {
    abstract val key: String
    abstract val owners: List<String>

    data class Text(
        val text: String,
        override val key: String = "",
        override val owners: List<String> = emptyList(),
        val onceKey: String? = null,
    ) : Post()

    data class Photo(
        val entryId: String,
        val file: String,
        val caption: String,
        override val key: String = "",
    ) : Post() {
        override val owners: List<String> get() = listOf(entryId)
    }
}

object Builder {

    fun ctx(d: AppData, e: HwEntry, today: LocalDate = LocalDate.now()): TplCtx {
        val lessonDate = Sched.parseDate(e.lessonDate) ?: today
        return TplCtx(
            subject = d.subject(e.subjectId),
            subjectName = e.subjectName,
            text = e.text,
            noHw = e.noHw,
            due = Sched.parseDate(e.due) ?: lessonDate,
            lessonDate = lessonDate,
            lessonNum = e.lessonNum.takeIf { it > 0 },
            photoCount = e.photos.size,
            today = today,
            now = LocalTime.now(),
            author = e.author,
        )
    }

    fun dayCtx(d: AppData, kind: HeaderDate, today: LocalDate = LocalDate.now()): TplCtx {
        val date = when (kind) {
            HeaderDate.TODAY -> today
            HeaderDate.NEXT_SCHOOL_DAY -> Sched.nextSchoolDay(d, today)
        }
        return TplCtx(due = date, lessonDate = today, today = today, now = LocalTime.now())
    }

    fun r(d: AppData, tpl: String, c: TplCtx): String {
        val s = Tpl.render(tpl, d, c)
        return if (d.templates.tidyUp) Tpl.tidy(s) else s
    }

    fun split(text: String, max: Int): List<String> {
        val m = max.coerceIn(100, 7000)
        if (text.length <= m) return listOf(text)
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in text.lines()) {
            var l = line
            while (l.length > m) {
                if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
                out += l.take(m)
                l = l.drop(m)
            }
            if (cur.length + l.length + 1 > m) { out += cur.toString(); cur.clear() }
            if (cur.isNotEmpty()) cur.append('\n')
            cur.append(l)
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
