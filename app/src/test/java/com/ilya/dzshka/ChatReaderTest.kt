package com.ilya.dzshka

import com.ilya.dzshka.work.ChatReader
import com.ilya.dzshka.work.ChatReader.Item
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class ChatReaderTest {
    private val H = 2000
    private val now = LocalTime.of(12, 30)

    @Test fun takesOnlyTodayUnderSeparator() {
        val items = listOf(
            Item("7Б ДЗ", 80, 120),
            Item("Вчера", 300, 330),
            Item("Математика №1-5", 350, 400), Item("15:10", 405, 420),
            Item("Сегодня", 600, 630),
            Item("Русский: упр. 45", 650, 700), Item("12:20", 705, 720),
            Item("Написать сообщение…", 1900, 1950),
        )
        val got = ChatReader.pick(items, "7Б ДЗ", H, now) { false }
        assertTrue(got.contains("Русский: упр. 45"))
        assertFalse(got.contains("Математика №1-5"))
    }

    @Test fun otherChatIgnored() {
        val items = listOf(Item("Мама", 80, 120), Item("Сегодня", 600, 630), Item("Русский: упр. 45", 650, 700))
        assertTrue(ChatReader.pick(items, "7Б ДЗ", H, now) { false }.isEmpty())
    }

    @Test fun noSeparatorNeedsFreshTimeAndUnseen() {
        val items = listOf(
            Item("7Б ДЗ", 80, 120),
            Item("Химия §3", 400, 450), Item("08:00", 455, 470),
            Item("Русский: упр. 45", 650, 700), Item("12:20", 705, 720),
            Item("ИКТУ стр. 10", 900, 950), Item("12:25", 955, 970),
        )
        val got = ChatReader.pick(items, "7Б ДЗ", H, now) { it.startsWith("ИКТУ") }
        assertEquals(listOf("Русский: упр. 45"), got)
    }

    @Test fun separators() {
        assertTrue(ChatReader.isTodaySep("Сегодня"))
        assertTrue(ChatReader.isOtherDaySep("10 ноября"))
        assertTrue(ChatReader.isOtherDaySep("Вчера"))
        assertFalse(ChatReader.isOtherDaySep("Математика №238"))
    }
}
