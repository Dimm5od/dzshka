package com.ilya.dzshka

import androidx.test.core.app.ApplicationProvider
import com.ilya.dzshka.data.*
import com.ilya.dzshka.link.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Основной + «помощник», который шлёт пакеты напрямую в ntfy. Нужен локальный ntfy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainLinkTest {
    private val server = System.getProperty("ntfy.url") ?: "http://127.0.0.1:2586"
    private fun serverUp() = runCatching {
        Ntfy.http.newCall(okhttp3.Request.Builder().url("$server/v1/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    @Test fun fullHelperFlow() {
        assumeTrue(serverUp())
        assumeTrue(!BuildConfig.HELPER)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Store.init(ctx)
        // одинаковое расписание каждый день, чтобы «сегодняшний урок» был всегда
        val base = Sched.importText(AppData(), (listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").joinToString("\n") { "$it: Математика, Физика" }), 0).data
        // студия: только вручную, чтобы ничего не уходило в Viber
        Store.replaceAll(base.copy(moments = Presets.list.last().build()))
        val code = "класс-${System.nanoTime()}"
        MainLink.setCode(code, "Илья", server)
        val sec = LinkCrypto.derive(code)
        assertEquals(sec.topic, Store.d.link.topic)
        assertNotEquals(code, Store.d.link.keyB64)

        fun helperSends(p: Packet) = Envelope.pack(sec.keyB64, Proto.encode(p)).forEach { Ntfy.publish(server, Proto.upTopic(sec.topic), it) }
        fun mainPolls() { Thread.sleep(250); Link.pollNow(ctx) }
        fun downPackets(): List<Packet> { Thread.sleep(250)
            return Ntfy.poll(server, Proto.downTopic(sec.topic), "all").mapNotNull { Envelope.feed(sec.keyB64, it.message)?.let(Proto::decode) } }

        // 1. помощник здоровается → появляется «ждёт одобрения», ему уходят права и расписание
        helperSends(Packet(Packet.HELLO, "petya", name = "Петя", model = "Redmi", version = "1.0"))
        mainPolls()
        val h = Store.d.helpers.single()
        assertEquals("Петя", h.name)
        assertEquals(HelperStatus.PENDING, h.status)
        val down1 = downPackets()
        assertTrue(down1.any { it.type == Packet.PERM && it.to == "petya" && it.status == HelperStatus.PENDING })
        val cfg = down1.first { it.type == Packet.CFG }.cfg!!
        assertEquals(listOf("Математика", "Физика"), cfg.subjects.map { it.name })

        val math = Store.d.subjects.first { it.name == "Математика" }
        val today = LocalDate.now().toString()
        fun hw(cid: String, photos: Boolean = true, date: String = today) = HwPayload(cid, math.id, math.name, "№5", false, "2099-01-01", date, 1,
            if (photos) listOf("https://files.catbox.moe/x.jpeg") else emptyList())

        // 2. пока не одобрен — ДЗ отклоняется
        helperSends(Packet(Packet.HW, "petya", hw = hw("c1")))
        mainPolls()
        assertTrue(Store.d.queue.isEmpty())
        assertEquals(AckState.REJECTED, downPackets().last { it.type == Packet.ACK }.ack)

        // 3. одобрили, но только физику → математика отклоняется
        val phys = Store.d.subjects.first { it.name == "Физика" }
        MainLink.updateHelper("petya") { it.copy(status = HelperStatus.ACTIVE, allowedSubjects = listOf(phys.id)) }
        helperSends(Packet(Packet.HW, "petya", hw = hw("c2")))
        mainPolls()
        assertTrue(Store.d.queue.isEmpty())

        // 3б. права по умолчанию: фото и другой день нельзя
        MainLink.updateHelper("petya") { it.copy(allowedSubjects = emptyList()) }
        helperSends(Packet(Packet.HW, "petya", hw = hw("p1")))
        mainPolls()
        assertTrue(Store.d.queue.isEmpty())
        assertEquals("фото тебе присылать нельзя", downPackets().last { it.type == Packet.ACK && it.clientId == "p1" }.note)
        helperSends(Packet(Packet.HW, "petya", hw = hw("p2", photos = false, date = LocalDate.now().minusDays(1).toString())))
        mainPolls()
        assertTrue(Store.d.queue.isEmpty())
        // текстом за сегодня — можно; дата сдачи считается сама, а не «2099»
        helperSends(Packet(Packet.HW, "petya", hw = hw("p3", photos = false)))
        mainPolls()
        assertNotEquals("2099-01-01", Store.d.queue.single().due)
        Store.update { it.copy(queue = emptyList()) }

        // 4. разрешили фото → ДЗ в очереди, с автором и фото-ссылкой
        MainLink.updateHelper("petya") { it.copy(perms = listOf(HelperPerm.PHOTOS)) }
        helperSends(Packet(Packet.HW, "petya", hw = hw("c3")))
        mainPolls()
        val e = Store.d.queue.single()
        assertEquals("Петя", e.author)
        assertEquals("https://files.catbox.moe/x.jpeg", e.uploaded[e.photos.single()])
        assertEquals(AckState.QUEUED, downPackets().last { it.type == Packet.ACK && it.clientId == "c3" }.ack)

        // повтор того же ДЗ не дублируется
        helperSends(Packet(Packet.HW, "petya", hw = hw("c3")))
        mainPolls()
        assertEquals(1, Store.d.queue.size)

        // 5. нужно одобрение → статус APPROVAL, потом reject убирает
        MainLink.updateHelper("petya") { it.copy(needsApproval = true) }
        helperSends(Packet(Packet.HW, "petya", hw = hw("c4")))
        mainPolls()
        val pending = Store.d.queue.first { it.clientId == "c4" }
        assertEquals(EntryStatus.APPROVAL, pending.status)
        MainLink.reject(ctx, pending.id)
        assertNull(Store.d.queue.firstOrNull { it.clientId == "c4" })

        // токен «КТО» подставляет имя помощника
        val st = Step.of(StepKind.HW_TEXT).copy(template = "П: Т (прислал КТО)")
        val post = Studio.plan(Store.d, listOf(st), listOf(e), LocalDate.now(), emptyMap()).single() as Post.Text
        assertEquals("Математика: №5 (прислал Петя)", post.text)

        // код виден у основного
        assertEquals(code, Store.d.link.code)

        // 6. смена кода — помощники отключаются
        MainLink.setCode("новый-${System.nanoTime()}", "Илья", server)
        assertTrue(Store.d.helpers.isEmpty())
    }
}
