package com.ilya.dzshka

import com.ilya.dzshka.data.AppData
import com.ilya.dzshka.data.Subject
import com.ilya.dzshka.data.Week
import com.ilya.dzshka.net.RemoteSchedule
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RemoteScheduleTest {
    private fun file() = RemoteSchedule.parse(
        listOf(File("../tools/schedule/schedule.json"), File("tools/schedule/schedule.json")).first { it.exists() }.readText()
    )

    @Test fun realFileParsesAsMonday() {
        val f = file()
        assertTrue(f.rev >= 1)
        val mon = f.days.single { it.day == 0 }
        assertEquals(7, mon.lessons.size)
        assertEquals("Математика", mon.lessons[1].name)
    }

    @Test fun existingSubjectsReusedNewCreated() {
        val math = Subject(name = "Математика")
        val rus = Subject(name = "Русский язык")
        val hist = Subject(name = "История")   // общая история — не путать с историей украинского народа
        val d0 = AppData(subjects = listOf(math, rus, hist), weeks = listOf(Week("1"), Week("2")))
        val (d, rep) = RemoteSchedule.apply(d0, file())
        val mon = d.weeks[0].days[0].lessons
        assertEquals(7, mon.size)
        assertEquals(math.id, mon[1])
        assertEquals(rus.id, mon[6])
        assertNotEquals(hist.id, mon[5])
        assertEquals(mon, d.weeks[1].days[0].lessons)          // во все недели
        assertEquals("Русский", d.subjects.first { it.id == rus.id }.name)   // переименован
        assertEquals(2, d.remoteSched.rev)
        assertTrue(rep.contains("Понедельник: 7"))
        assertEquals(3 + 5, d.subjects.size)
        // вторник не тронут
        assertEquals(d0.weeks[0].days[1], d.weeks[0].days[1])
    }

    @Test fun oldRev1NamesGetRenamed() {
        val old = listOf("Воспитание для общества", "Математика", "Изобразительное искусство", "Личностное развитие",
            "Румынский язык и литература", "История, культура и традиции украинского народа", "Русский язык и литература").map { Subject(name = it) }
        val (d, _) = RemoteSchedule.apply(AppData(subjects = old), file())
        assertEquals(7, d.subjects.size)
        assertEquals(listOf("Общественное воспитание", "Математика", "Рисование", "Развитие личности", "Румынский", "ИКТУ", "Русский"),
            d.weeks[0].days[0].lessons.map { id -> d.subjects.first { it.id == id }.name })
    }

    @Test fun reapplyDoesNotDuplicate() {
        val (d1, _) = RemoteSchedule.apply(AppData(), file())
        val (d2, _) = RemoteSchedule.apply(d1, file())
        assertEquals(d1.subjects.size, d2.subjects.size)
        assertEquals(d1.weeks, d2.weeks)
    }
}
