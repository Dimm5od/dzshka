package com.ilya.dzshka

import com.ilya.dzshka.data.AppData
import com.ilya.dzshka.mascot.CatWidget
import com.ilya.dzshka.net.RemoteSchedule
import com.ilya.dzshka.ui.NowState
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class NowStateTest {
    private fun data(): AppData {
        val f = RemoteSchedule.parse(listOf(File("../tools/schedule/schedule.json"), File("tools/schedule/schedule.json")).first { it.exists() }.readText())
        return RemoteSchedule.apply(AppData(), f).first
    }

    @Test fun mondayFourthLesson() {
        val i = NowState.compute(data(), LocalDateTime.of(2026, 11, 2, 11, 53))
        assertEquals("Идёт 4 урок", i.big)
        assertTrue(i.small, i.small.contains("Развитие личности") && i.small.contains("осталось 12 мин"))
        assertTrue(i.next, i.next.contains("5 урок") && i.next.contains("Румынский"))
        assertEquals("4 урок", i.short1); assertEquals("12 мин", i.short2)
        assertTrue(i.live)
        assertNotNull(i.prog)
    }

    @Test fun breakAndAfter() {
        val br = NowState.compute(data(), LocalDateTime.of(2026, 11, 2, 12, 10))
        assertTrue(br.big, br.big.startsWith("Перемена"))
        assertEquals("10 мин", br.short2)
        val after = NowState.compute(data(), LocalDateTime.of(2026, 11, 2, 16, 0))
        assertFalse(after.live)
        assertEquals(LocalDateTime.of(2026, 11, 3, 0, 0, 5), CatWidget.nextTick(false, LocalDateTime.of(2026, 11, 2, 16, 0, 12)))
        assertEquals(LocalDateTime.of(2026, 11, 2, 11, 54), CatWidget.nextTick(true, LocalDateTime.of(2026, 11, 2, 11, 53, 40)))
    }
}
