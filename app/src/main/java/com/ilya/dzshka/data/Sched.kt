package com.ilya.dzshka.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class Lesson(
    val date: LocalDate,
    val index: Int,          // 0-based позиция в дне
    val number: Int,         // номер урока для людей
    val subjectId: String,
    val start: Int?,         // минуты от полуночи
    val end: Int?,
)

data class Reminder(val time: LocalDateTime, val lesson: Lesson)

object Sched {

    fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s) }.getOrNull()

    fun isHoliday(d: AppData, date: LocalDate): Boolean = d.holidays.any {
        val f = parseDate(it.from) ?: return@any false
        val t = parseDate(it.to) ?: f
        !date.isBefore(f) && !date.isAfter(t)
    }

    fun weekIndex(d: AppData, date: LocalDate): Int {
        if (d.weeks.size <= 1) return 0
        val anchor = (parseDate(d.weekAnchor) ?: LocalDate.of(2026, 9, 1))
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val w = ChronoUnit.WEEKS.between(anchor, monday)
        return (((w % d.weeks.size) + d.weeks.size) % d.weeks.size).toInt()
    }

    fun day(d: AppData, date: LocalDate): DaySchedule? =
        d.weeks.getOrNull(weekIndex(d, date))?.days?.getOrNull(date.dayOfWeek.value - 1)

    fun lessonsOn(d: AppData, date: LocalDate, includeEmpty: Boolean = false): List<Lesson> {
        if (isHoliday(d, date)) return emptyList()
        val day = day(d, date) ?: return emptyList()
        if (!day.enabled) return emptyList()
        val bell = d.bell(day.bellId)
        return day.lessons.mapIndexedNotNull { i, sid ->
            if (sid.isBlank() && !includeEmpty) return@mapIndexedNotNull null
            val slot = bell?.slots?.getOrNull(i)
            Lesson(date, i, i + d.firstLessonNum, sid, slot?.let { Time.parse(it.start) }, slot?.let { Time.parse(it.end) })
        }
    }

    fun isSchoolDay(d: AppData, date: LocalDate) = lessonsOn(d, date).isNotEmpty()

    fun nextLessonDate(d: AppData, subjectId: String, after: LocalDate): LocalDate? {
        var x = after.plusDays(1)
        repeat(120) {
            if (lessonsOn(d, x).any { it.subjectId == subjectId }) return x
            x = x.plusDays(1)
        }
        return null
    }

    fun nextSchoolDay(d: AppData, after: LocalDate): LocalDate {
        var x = after.plusDays(1)
        repeat(120) {
            if (isSchoolDay(d, x)) return x
            x = x.plusDays(1)
        }
        return after.plusDays(1)
    }

    fun dueFor(d: AppData, mode: DueMode, subjectId: String, lessonDate: LocalDate): LocalDate = when (mode) {
        DueMode.NEXT_LESSON -> nextLessonDate(d, subjectId, lessonDate) ?: nextSchoolDay(d, lessonDate)
        DueMode.NEXT_SCHOOL_DAY -> nextSchoolDay(d, lessonDate)
        DueMode.TOMORROW -> lessonDate.plusDays(1)
        DueMode.TODAY -> lessonDate
    }

    /** Урок, который идёт сейчас или закончился последним (сегодня). */
    fun currentOrLast(d: AppData, now: LocalDateTime): Lesson? {
        val m = now.hour * 60 + now.minute
        val list = lessonsOn(d, now.toLocalDate())
        list.firstOrNull { it.start != null && it.end != null && m in it.start..it.end }?.let { return it }
        return list.filter { it.end != null && it.end <= m }.maxByOrNull { it.end!! }
    }

    fun reminders(d: AppData, from: LocalDateTime, days: Int = 14): List<Reminder> {
        val cfg = d.reminder
        if (!cfg.enabled) return emptyList()
        val out = mutableListOf<Reminder>()
        for (k in 0..days) {
            val date = from.toLocalDate().plusDays(k.toLong())
            val list = lessonsOn(d, date)
            list.forEachIndexed { idx, l ->
                if (l.end == null) return@forEachIndexed
                val subj = d.subject(l.subjectId) ?: return@forEachIndexed
                if (!subj.remind || subj.noSend) return@forEachIndexed
                val next = list.getOrNull(idx + 1)
                if (cfg.mergeDouble && next != null && next.subjectId == l.subjectId && next.index == l.index + 1) return@forEachIndexed
                if (!cfg.remindLast && idx == list.lastIndex) return@forEachIndexed
                val t = date.atStartOfDay().plusMinutes((l.end + cfg.offsetMin).toLong())
                if (t.isAfter(from)) out += Reminder(t, l)
            }
            if (out.isNotEmpty() && k > 0) break
        }
        return out.sortedBy { it.time }
    }

    // ---------- импорт расписания текстом ----------

    data class ImportResult(val data: AppData, val report: String)

    private val dayAliases: List<Pair<Int, List<String>>> = listOf(
        0 to listOf("понедельник", "пн", "понеділок", "luni", "lu", "monday", "mon", "mo"),
        1 to listOf("вторник", "вт", "вівторок", "marți", "marti", "ma", "tuesday", "tue", "tu"),
        2 to listOf("среда", "ср", "середа", "miercuri", "mi", "wednesday", "wed", "we"),
        3 to listOf("четверг", "чт", "четвер", "joi", "jo", "thursday", "thu", "th"),
        4 to listOf("пятница", "пт", "пʼятниця", "п'ятниця", "пятниця", "vineri", "vi", "friday", "fri", "fr"),
        5 to listOf("суббота", "сб", "субота", "sâmbătă", "sambata", "sâ", "sa", "saturday", "sat"),
        6 to listOf("воскресенье", "вс", "неділя", "нд", "duminică", "duminica", "du", "sunday", "sun", "su"),
    )

    private fun matchDay(word: String, d: AppData): Int? {
        val w = word.lowercase().trim().trimEnd('.', ':')
        d.weekdays.forEachIndexed { i, s -> if (s.lowercase() == w) return i }
        d.weekdaysShort.forEachIndexed { i, s -> if (s.lowercase() == w) return i }
        return dayAliases.firstOrNull { (_, al) -> w in al }?.first
    }

    private val timeRe = Regex("""^(\d{1,2}[:.]\d{2})\s*[-–—]\s*(\d{1,2}[:.]\d{2})\s*(.*)$""")
    private val numRe = Regex("""^\d{1,2}\s*[.)\-:]\s*""")
    private val emptyWords = setOf("-", "—", "–", "окно", "нет", "пусто", "x", "х")

    fun importText(src: AppData, text: String, weekIdx: Int): ImportResult {
        var d = src
        val subjects = d.subjects.toMutableList()
        val days = mutableMapOf<Int, MutableList<String>>()
        val dayTimes = mutableMapOf<Int, MutableList<Slot>>()
        var cur: Int? = null
        var created = 0

        fun subjIdFor(nameRaw: String): String {
            val name = nameRaw.trim().trimEnd('.', ',', ';')
            if (name.lowercase() in emptyWords || name.isEmpty()) return ""
            subjects.firstOrNull { it.name.equals(name, true) || (it.short.isNotBlank() && it.short.equals(name, true)) }
                ?.let { return it.id }
            val s = Subject(name = name.replaceFirstChar { it.uppercase() })
            subjects += s
            created++
            return s.id
        }

        fun addLesson(raw: String) {
            val c = cur ?: return
            var item = raw.trim().replace(numRe, "")
            var slot: Slot? = null
            timeRe.find(item)?.let {
                slot = Slot(it.groupValues[1].replace('.', ':').padStart(5, '0'), it.groupValues[2].replace('.', ':').padStart(5, '0'))
                item = it.groupValues[3].trim()
            }
            if (item.isEmpty() && slot == null) return
            days.getOrPut(c) { mutableListOf() } += subjIdFor(item)
            slot?.let { dayTimes.getOrPut(c) { mutableListOf() } += it }
        }

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val head = line.split(Regex("""[:\s—–-]+"""), limit = 2)
            val di = matchDay(head[0], d)
            if (di != null) {
                cur = di
                days[di] = mutableListOf()
                val rest = line.substring(head[0].length).trimStart(':', ' ', '-', '—', '–')
                if (rest.isNotBlank()) rest.split(',', ';').forEach { addLesson(it) }
            } else if (line.contains(',') || line.contains(';')) {
                line.split(',', ';').forEach { addLesson(it) }
            } else addLesson(line)
        }

        val bells = d.bells.toMutableList()
        val weeks = d.weeks.toMutableList()
        while (weeks.size <= weekIdx) weeks += Week("Неделя ${weeks.size + 1}")
        val wk = weeks[weekIdx]
        val newDays = wk.days.toMutableList()
        days.forEach { (di, lessons) ->
            var bellId = newDays[di].bellId
            val slots = dayTimes[di]
            if (!slots.isNullOrEmpty() && slots.size == lessons.size) {
                val same = bells.firstOrNull { it.slots == slots }
                bellId = same?.id ?: BellProfile(name = "Звонки (${d.weekdaysShort.getOrElse(di) { "$di" }})", slots = slots)
                    .also { bells += it }.id
            }
            newDays[di] = newDays[di].copy(enabled = true, lessons = lessons, bellId = bellId)
        }
        weeks[weekIdx] = wk.copy(days = newDays)
        d = d.copy(subjects = subjects, weeks = weeks, bells = bells)
        val rep = buildString {
            append("Дней: ${days.size}, уроков: ${days.values.sumOf { it.size }}")
            if (created > 0) append(", новых предметов: $created")
            if (dayTimes.isNotEmpty()) append(", время звонков взято из текста")
        }
        return ImportResult(d, rep)
    }
}
