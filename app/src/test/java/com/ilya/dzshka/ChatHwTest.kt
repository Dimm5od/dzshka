package com.ilya.dzshka

import androidx.test.core.app.ApplicationProvider
import com.ilya.dzshka.data.*
import com.ilya.dzshka.work.ChatListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatHwTest {
    private val math = Subject(id = "m", name = "Математика")
    private val rus = Subject(id = "r", name = "Русский язык", short = "Рус")
    private val chem = Subject(id = "c", name = "Химия")
    private val phys = Subject(id = "f", name = "Физика")
    private val pe = Subject(id = "p", name = "Физкультура")

    @Test fun matching() {
        assertTrue(ChatListener.mentions("по математике №238", math))
        assertTrue(ChatListener.mentions("Математика: стр 5", math))
        assertTrue(ChatListener.mentions("русский упр 3", rus))
        assertTrue(ChatListener.mentions("рус. яз — выучить", rus))
        assertTrue(ChatListener.mentions("по химии §12", chem))
        assertTrue(ChatListener.mentions("физика 5 задач", phys))
        assertFalse(ChatListener.mentions("на физкультуру форму", phys))
        assertTrue(ChatListener.mentions("на физкультуру форму", pe))
        assertFalse(ChatListener.mentions("матч в субботу", math))
        assertFalse(ChatListener.mentions("привет всем", chem))
    }

    @Test fun extractText() {
        val all = listOf(math, rus, chem, phys, pe)
        assertEquals("№238, 240", ChatListener.extract("Математика: №238, 240", math, all))
        assertEquals("№238, 240", ChatListener.extract("Петя: Математика - №238, 240", math, all))
        val multi = "ДЗ на завтра:\nМатематика: №5, 6\nХимия — §12, вопросы 1-4\nРус. яз. упр. 112"
        assertEquals("№5, 6", ChatListener.extract(multi, math, all))
        assertEquals("§12, вопросы 1-4", ChatListener.extract(multi, chem, all))
        assertEquals("упр. 112", ChatListener.extract(multi, rus, all))
        assertEquals("№5", ChatListener.extract("по математике №5 и по химии §3", math, all))
        assertEquals("§3", ChatListener.extract("по математике №5 и по химии §3", chem, all))
        assertEquals("Русский язык упр 5".let { "упр 5" }, ChatListener.extract("Русский язык: упр 5", rus, all))
        assertEquals("№7", ChatListener.extract("№7 по физике", phys, all))
        assertNull(ChatListener.extract("привет", math, all))
    }

    @Test fun handleMarksTodayOnlyAndChosenChat() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Store.init(ctx)
        val days = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").joinToString("\n") { "$it: Математика, Химия" }
        val base = Sched.importText(AppData(), days, 0).data
        Store.replaceAll(base.copy(chatHw = ChatHwCfg(enabled = true), viber = ViberCfg(channelName = "ДЗ 7-Б")))
        val m = Store.d.subjects.first { it.name == "Математика" }
        val c = Store.d.subjects.first { it.name == "Химия" }
        val now = System.currentTimeMillis()
        val yesterday = now - 26 * 3600_000L
        // чужой чат — не считается, но запоминается в списке
        ChatListener.handle(ctx, listOf("Мама"), listOf("математика?" to now))
        assertTrue(Store.d.fromChat.isEmpty())
        assertEquals(listOf("Мама"), Store.d.chatHw.seen)
        // нужный чат: химия была вчера — не считается, математика сегодня — да
        ChatListener.handle(ctx, listOf("ДЗ 7-Б"), listOf("Химия §3" to yesterday, "Петя: Математика №238, 240" to now))
        val today = LocalDate.now()
        assertEquals("№238, 240", Store.d.fromChat["$today|${m.id}"])
        assertFalse(Store.d.isFromChat(c.id, today.toString()))
        // выглядит как отправленное из приложения
        assertTrue(Store.d.isSent(m.id, today.toString()))
        assertEquals("№238, 240", Store.d.hwLog["$today|${m.id}"])
        assertEquals("№238, 240", Store.d.hwInfo["$today|${m.id}"]?.text)
        assertTrue(Store.d.hwInfo["$today|${m.id}"]!!.due.isNotBlank())
        assertEquals("Математика: №238, 240", Store.d.history.first().text)
        // если ДЗ уже в очереди приложения — сообщение из чата не трогает
        Store.update { it.copy(fromChat = emptyMap(), sentKeys = emptyList(), queue = listOf(HwEntry(subjectId = c.id, subjectName = "Химия", text = "x", lessonDate = today.toString()))) }
        ChatListener.handle(ctx, listOf("ДЗ 7-Б"), listOf("Химия §5" to now))
        assertFalse(Store.d.isSent(c.id, today.toString()))
        // выключено — ничего не делает
        Store.update { it.copy(chatHw = it.chatHw.copy(enabled = false), fromChat = emptyMap()) }
    }
}
