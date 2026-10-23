package com.ilya.dzshka.mascot

import com.ilya.dzshka.data.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

/** Что сейчас сказать коту: по времени, дню недели, каникулам, ДЗ на завтра и событиям. */
object Mascot {
    enum class Mood { SIT, HAPPY, SLEEP, WOW, WAVE }

    data class Line(val text: String, val mood: Mood = Mood.SIT)

    data class Ctx(
        val now: LocalDateTime,
        val schoolToday: Boolean,
        val holidayToday: Boolean,
        val holidayYesterday: Boolean,
        val holidayTomorrow: Boolean,
        val schoolTomorrow: Boolean,
        val daysToHoliday: Int?,
        /** ДЗ на следующий учебный день */
        val nextDay: LocalDate,
        val hwNext: List<HwRecord>,
        val event: String,
    )

    fun ctx(d: AppData, now: LocalDateTime = LocalDateTime.now()): Ctx {
        val today = now.toLocalDate()
        val next = Sched.nextSchoolDay(d, today)
        val hw = Archive.items(d).map { it.rec }.filter { !it.none && (Sched.parseDate(it.due) == next) }
        val holidayToday = Sched.isHoliday(d, today)
        var toHoliday: Int? = null
        if (!holidayToday) for (i in 1..60) if (Sched.isHoliday(d, today.plusDays(i.toLong()))) { toHoliday = i; break }
        val ev = d.mascot.event.takeIf { System.currentTimeMillis() - d.mascot.eventAt < 2 * 3600_000 } ?: ""
        return Ctx(
            now = now,
            schoolToday = Sched.isSchoolDay(d, today) && !holidayToday,
            holidayToday = holidayToday,
            holidayYesterday = Sched.isHoliday(d, today.minusDays(1)),
            holidayTomorrow = Sched.isHoliday(d, today.plusDays(1)),
            schoolTomorrow = Sched.isSchoolDay(d, today.plusDays(1)) && !Sched.isHoliday(d, today.plusDays(1)),
            daysToHoliday = toHoliday,
            nextDay = next,
            hwNext = hw,
            event = ev,
        )
    }

    private val ACC = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")

    fun fill(t: String, d: AppData, c: Ctx, subject: String? = null): String = t
        .replace("{cat}", d.mascot.name.ifBlank { "Кот" })
        .replace("{subject}", subject ?: c.hwNext.firstOrNull()?.subjectName?.ifBlank { null } ?: "математика")
        .replace("{n}", c.hwNext.size.toString())
        .replace("{days}", (c.daysToHoliday ?: 0).toString())
        .replace("{tomorrow}", ACC[c.nextDay.dayOfWeek.value - 1])

    /** Пулы реплик, которые подходят к моменту. Вес — сколько раз пул «кладётся в шляпу». */
    fun pools(c: Ctx): List<Pair<List<String>, Mood>> {
        val h = c.now.hour
        val dow = c.now.dayOfWeek
        val out = mutableListOf<Pair<List<String>, Mood>>()
        fun add(p: List<String>, m: Mood = Mood.SIT, w: Int = 1) = repeat(w) { if (p.isNotEmpty()) out += p to m }
        if (c.event == "hwSent") add(Phrases.hwSent, Mood.HAPPY, 4)
        if (c.event == "noHwGiven") add(Phrases.noHwGiven, Mood.HAPPY, 3)
        if (h >= 22 || h < 6) { add(Phrases.night, Mood.SLEEP, 4); add(Phrases.random, Mood.SIT, 1); return out }
        when {
            c.holidayToday && !c.holidayYesterday -> add(Phrases.holidayFirst, Mood.HAPPY, 4)
            c.holidayToday && !c.holidayTomorrow -> { add(Phrases.holidayLast, Mood.WOW, 4); hwPools(c, ::add) }
            c.holidayToday -> add(Phrases.holiday, Mood.HAPPY, 4)
            dow == DayOfWeek.SATURDAY -> add(Phrases.weekendSat, Mood.HAPPY, 3)
            dow == DayOfWeek.SUNDAY -> { add(Phrases.weekendSun, Mood.SIT, 3); if (h >= 14) hwPools(c, ::add) }
            else -> {
                when (h) {
                    in 6..8 -> add(Phrases.morning, Mood.WAVE, 3)
                    in 9..14 -> add(Phrases.day, Mood.SIT, 3)
                    else -> add(Phrases.evening, Mood.SIT, 3)
                }
                if (dow == DayOfWeek.FRIDAY && h >= 13 || !c.schoolTomorrow && h >= 13) add(Phrases.weekendFri, Mood.HAPPY, 2)
                if (h >= 12) hwPools(c, ::add)
            }
        }
        c.daysToHoliday?.let { if (it in 1..14) add(Phrases.holidaySoon, Mood.WOW, if (it <= 5) 3 else 1) }
        add(Phrases.random, Mood.SIT, 2)
        return out
    }

    private fun hwPools(c: Ctx, add: (List<String>, Mood, Int) -> Unit) {
        when {
            c.hwNext.isEmpty() -> add(Phrases.hwNone, Mood.HAPPY, 2)
            c.hwNext.size >= 4 -> add(Phrases.hwMany, Mood.WOW, 3)
            else -> add(Phrases.hwFew, Mood.SIT, 2)
        }
        if (c.hwNext.isNotEmpty()) add(Phrases.hwSubject, Mood.SIT, 2)
    }

    private fun pick(pools: List<Pair<List<String>, Mood>>, r: Random, d: AppData, c: Ctx, avoid: String? = null): Line {
        if (pools.isEmpty()) return Line("Мур.")
        // если следующий учебный день не завтра (пятница, перед каникулами) — не говорим «на завтра» про ДЗ
        val tomorrowIsSchool = c.nextDay == c.now.toLocalDate().plusDays(1)
        repeat(10) {
            val (p, m) = pools[r.nextInt(pools.size)]
            val subj = c.hwNext.takeIf { it.isNotEmpty() }?.let { it[r.nextInt(it.size)].subjectName }
            val t = fill(p[r.nextInt(p.size)], d, c, subj)
            val wrongDay = !tomorrowIsSchool && t.contains("завтр", ignoreCase = true) && p !== Phrases.weekendSun && p !== Phrases.holidayLast
            if (t != avoid && !wrongDay) return Line(t, m)
        }
        val (p, m) = pools.first()
        return Line(fill(p.first(), d, c), m)
    }

    /** Реплика дня: одна и та же в течение часа-двух, меняется со временем суток. */
    fun daily(d: AppData, now: LocalDateTime = LocalDateTime.now()): Line {
        val c = ctx(d, now)
        val seed = now.toLocalDate().toEpochDay() * 31 + now.hour / 2 + c.event.hashCode()
        return pick(pools(c), Random(seed), d, c)
    }

    /** Новая случайная реплика (нажали на кота). */
    fun another(d: AppData, avoid: String? = null): Line { val c = ctx(d); return pick(pools(c), Random.Default, d, c, avoid) }

    fun react(d: AppData, list: List<String>, mood: Mood, avoid: String? = null): Line {
        val c = ctx(d)
        var t = fill(list.random(), d, c)
        if (t == avoid && list.size > 1) t = fill(list.random(), d, c)
        return Line(t, mood)
    }

    fun isNight(now: LocalDateTime = LocalDateTime.now()) = now.hour >= 22 || now.hour < 6

    /** Строка «ДЗ на завтра» для виджета. */
    fun hwLine(d: AppData): String {
        val c = ctx(d)
        val day = if (c.nextDay == c.now.toLocalDate().plusDays(1)) "завтра" else "${ACC[c.nextDay.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() }}"
        val pre = if (day == "завтра") "На завтра" else "На $day"
        return if (c.hwNext.isEmpty()) "$pre ДЗ не записано" else "$pre: " + c.hwNext.joinToString(", ") { it.subjectName.ifBlank { "?" } }
    }
}
