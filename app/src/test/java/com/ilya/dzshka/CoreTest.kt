package com.ilya.dzshka

import com.ilya.dzshka.data.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CoreTest {
    private val jan23 = LocalDate.of(2027, 1, 23)

    @Test fun userExample() {
        val d = AppData(monthsA = Defaults.RU_MONTHS_NOM)
        assertEquals("23 за январь", Tpl.render("Ч за М", d, TplCtx(due = jan23)))
    }

    @Test fun wholeWordsDontBreakText() {
        val d = AppData()
        val c = TplCtx(subjectName = "Математика", text = "№238", due = LocalDate.of(2026, 10, 16))
        assertEquals("ДЗ на 16 октября (пятница)\nМатематика: №238", Tpl.render("ДЗ на Ч М (Д)\nП: Т", d, c))
        // буквы внутри слов не заменяются, текст ДЗ не обрабатывается повторно
        val c2 = c.copy(text = "Ч М П")
        assertEquals("Математика — Ч М П", Tpl.render("П — Т", d, c2))
        assertEquals("ДЗ Домашка", Tpl.render("ДЗ Домашка", d, c))
    }

    @Test fun escapesAndCustom() {
        val d = AppData(tokens = Defaults.tokens() + TokenDef(text = "{класс}", type = TokenType.CUSTOM, value = "7-Б"))
        assertEquals("Ч 7-Б\n5", Tpl.render("\\Ч {класс}\\nЧ", d, TplCtx(due = LocalDate.of(2026, 1, 5))))
    }

    @Test fun tidyRemovesEmptyEmoji() {
        val d = AppData()
        val e = HwEntry(subjectName = "Химия", text = "стр. 5", due = "2026-10-19", lessonDate = "2026-10-16")
        val st = Step.of(StepKind.HW_TEXT).copy(template = "📚 ДЗ на Ч М (Д)\nЭ П: Т")
        val p = Studio.plan(d, listOf(st), listOf(e), LocalDate.of(2026, 10, 16), emptyMap())
        assertEquals("📚 ДЗ на 19 октября (понедельник)\nХимия: стр. 5", (p.single() as Post.Text).text)
    }

    private fun sample(): AppData {
        val r = Sched.importText(AppData(), """
            Пн: Математика, Русский язык, Физика
            Вт
            1. 08:30-09:15 Физика
            2. 09:25-10:10 Математика
            3. 10:30-11:15 —
            4. 11:25-12:10 История
            Ср: история, физкультура
        """.trimIndent(), 0)
        return r.data
    }

    @Test fun importAndDue() {
        val d = sample()
        assertEquals(5, d.subjects.size)
        val math = d.subjects.first { it.name == "Математика" }.id
        val mon = LocalDate.of(2026, 10, 19)
        assertEquals(LocalDate.of(2026, 10, 20), Sched.nextLessonDate(d, math, mon))
        assertEquals(LocalDate.of(2026, 10, 26), Sched.nextLessonDate(d, math, mon.plusDays(1)))
        val tue = Sched.lessonsOn(d, mon.plusDays(1))
        assertEquals(3, tue.size)
        assertEquals(4, tue.last().number)
        assertEquals(11 * 60 + 25, tue.last().start)
        assertTrue(d.bells.size == 2)
    }

    @Test fun holidaysAndReminders() {
        val d0 = sample()
        val d = d0.copy(holidays = listOf(Holiday(from = "2026-10-26", to = "2026-11-01")))
        val math = d.subjects.first { it.name == "Математика" }.id
        assertEquals(LocalDate.of(2026, 11, 2), Sched.nextLessonDate(d, math, LocalDate.of(2026, 10, 20)))
        val rem = Sched.reminders(d, LocalDateTime.of(2026, 10, 20, 9, 0))
        assertEquals(LocalDateTime.of(2026, 10, 20, 9, 16), rem.first().time) // через минуту после звонка
    }

    @Test fun twoWeeks() {
        val d = sample().let { it.copy(weeks = it.weeks + Week("Неделя 2"), weekAnchor = "2026-10-19") }
        assertEquals(0, Sched.weekIndex(d, LocalDate.of(2026, 10, 21)))
        assertEquals(1, Sched.weekIndex(d, LocalDate.of(2026, 10, 28)))
        assertEquals(1, Sched.weekIndex(d, LocalDate.of(2026, 10, 14)))
    }

    private val fri = LocalDate.of(2026, 10, 16)
    private fun es() = listOf(
        HwEntry(subjectName = "Физика", text = "§3", due = "2026-10-20", lessonDate = "2026-10-16", lessonNum = 1, photos = listOf("a.jpeg")),
        HwEntry(subjectName = "Химия", noHw = true, due = "2026-10-20", lessonDate = "2026-10-16", lessonNum = 2),
    )

    @Test fun allInOne() {
        val d = AppData()
        val p = Studio.plan(d, listOf(Step.of(StepKind.ALL_TEXT), Step.of(StepKind.ALL_PHOTOS)), es(), fri, emptyMap())
        assertEquals("📅 ДЗ на 20 октября, вторник\nФизика: §3\nХимия: нет ДЗ 🎉", (p[0] as Post.Text).text)
        assertEquals("Физика — ДЗ на 20 октября", (p[1] as Post.Photo).caption)
        assertEquals(2, p.size)
    }

    @Test fun headerThenEachSeparately() {
        val d = AppData()
        val steps = listOf(Step.of(StepKind.DAY_HEADER), Step.of(StepKind.HW_TEXT), Step.of(StepKind.HW_PHOTOS))
        val p = Studio.plan(d, steps, es(), fri, emptyMap())
        assertEquals(listOf("📅 ДЗ за 16 октября (пятница)", "Физика: §3", "Химия: нет ДЗ 🎉"), p.filterIsInstance<Post.Text>().map { it.text })
        assertEquals(1, p.filterIsInstance<Post.Photo>().size)
        // заголовок уже был сегодня — не повторяется
        val p2 = Studio.plan(d, steps, es(), fri, mapOf(Studio.ONCE_HEADER to fri.toString()))
        assertEquals("Физика: §3", (p2[0] as Post.Text).text)
    }

    @Test fun captionOnPhoto() {
        val d = AppData()
        val p = Studio.plan(d, listOf(Step.of(StepKind.HW_WITH_PHOTO)), es(), fri, emptyMap())
        assertEquals("Физика: §3", (p[0] as Post.Photo).caption)
        assertEquals("Химия: нет ДЗ 🎉", (p[1] as Post.Text).text)
    }

    @Test fun simulateDefaultPreset() {
        val d = sample()
        val date = LocalDate.of(2026, 10, 20) // вторник: Физика, Математика, окно, История
        val ls = Sched.lessonsOn(d, date)
        val e1 = HwEntry(subjectId = ls[0].subjectId, subjectName = "Физика", text = "§3", lessonDate = date.toString(), due = "2026-10-21")
        val e2 = HwEntry(subjectId = ls[1].subjectId, subjectName = "Математика", text = "№5", lessonDate = date.toString(), due = "2026-10-26")
        val sim = Studio.simulate(d, date, listOf(e1, e2), mapOf(e1.id to ls[0].end!!, e2.id to ls[1].end!!))
        assertEquals(listOf("08:30", "09:15", "10:10"), sim.map { it.time })
        assertEquals("📅 ДЗ за 20 октября (вторник)", (sim[0].post as Post.Text).text)
        assertEquals("Физика: §3", (sim[1].post as Post.Text).text)
    }

    @Test fun nextFire() {
        val d = sample()
        val m = Moment(trigger = Trigger.AFTER_LAST, offsetMin = 10)
        assertEquals(LocalDateTime.of(2026, 10, 20, 12, 20), Studio.nextFire(d, m, LocalDateTime.of(2026, 10, 20, 9, 0)))
        val f = Moment(trigger = Trigger.FIRST_LESSON, offsetMin = -5)
        assertEquals(LocalDateTime.of(2026, 10, 21, 8, 25), Studio.nextFire(d, f, LocalDateTime.of(2026, 10, 20, 9, 0)))
    }

    @Test fun weekEvents() {
        val d = sample() // пн/вт/ср, пресет по умолчанию: заголовок к первому уроку + каждое ДЗ сразу
        val mon = com.ilya.dzshka.work.TestRun.weekStart(LocalDate.of(2026, 10, 22), false)
        assertEquals(LocalDate.of(2026, 10, 19), mon)
        val ev = com.ilya.dzshka.work.TestRun.buildEvents(d, mon)
        val lessonEnds = ev.filter { it.kind == com.ilya.dzshka.work.TestRun.Kind.LESSON_END }
        val moments = ev.filter { it.kind == com.ilya.dzshka.work.TestRun.Kind.MOMENT }
        assertEquals(3, moments.size)            // утро в пн, вт, ср
        assertEquals(3 + 3 + 2, lessonEnds.size) // пн 3 урока, вт 3 (окно не считается), ср 2
        assertTrue(ev.zipWithNext().all { (a, b) -> !a.time.isAfter(b.time) })
        assertEquals(LocalDateTime.of(2026, 10, 19, 8, 30), ev.first().time)
    }

    @Test fun split() {
        val parts = Builder.split((1..50).joinToString("\n") { "строка номер $it" }, 100)
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(50, parts.joinToString("\n").lines().size)
    }
}
