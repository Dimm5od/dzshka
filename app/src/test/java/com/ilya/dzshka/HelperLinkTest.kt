package com.ilya.dzshka

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import androidx.work.workDataOf
import com.ilya.dzshka.data.*
import com.ilya.dzshka.link.*
import com.ilya.dzshka.work.Entries
import com.ilya.dzshka.work.HelperSendWorker
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.Executors

/** Помощник + «основной», который отвечает напрямую через ntfy. Только для сборки помощника. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HelperLinkTest {
    private val server = System.getProperty("ntfy.url") ?: "http://127.0.0.1:2586"
    private fun serverUp() = runCatching {
        Ntfy.http.newCall(okhttp3.Request.Builder().url("$server/v1/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    @Test fun helperFlow() {
        assumeTrue(serverUp())
        assumeTrue(BuildConfig.HELPER)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        Store.init(ctx)
        Store.replaceAll(AppData())
        val code = "помощь-${System.nanoTime()}"
        HelperLink.connect(code, "Петя", server)
        val sec = LinkCrypto.derive(code)
        assertEquals(sec.topic, Store.d.link.topic)

        fun upPackets() = Ntfy.poll(server, Proto.upTopic(sec.topic), "all").mapNotNull { Envelope.feed(sec.keyB64, it.message)?.let(Proto::decode) }
        fun mainSends(p: Packet) = Envelope.pack(sec.keyB64, Proto.encode(p)).forEach { Ntfy.publish(server, Proto.downTopic(sec.topic), it) }

        // 1. привет уходит основному
        HelperLink.hello()
        Thread.sleep(250)
        val hello = upPackets().single { it.type == Packet.HELLO }
        assertEquals("Петя", hello.name)
        val me = hello.from

        // 2. основной присылает права и расписание
        val mainData = Sched.importText(AppData(), "Пн: Математика, Физика\nВт: Химия", 0).data.copy(viber = ViberCfg(token = "SECRET", channelName = "ДЗ 7-Б"), link = LinkCfg(myName = "Илья"))
        mainSends(Packet(Packet.PERM, "main", to = me, status = HelperStatus.ACTIVE, name = "Илья"))
        mainSends(Packet(Packet.PERM, "main", to = "someone-else", status = HelperStatus.BLOCKED))
        mainSends(Packet(Packet.CFG, "main", cfg = Proto.cfgFrom(mainData)))
        Thread.sleep(250)
        Link.pollNow(ctx)
        assertEquals(HelperStatus.ACTIVE, Store.d.link.myStatus) // чужой BLOCKED не применился
        assertEquals("Илья", Store.d.link.mainName)
        assertEquals(listOf("Математика", "Физика", "Химия"), Store.d.subjects.map { it.name })
        assertEquals("", Store.d.viber.token)
        assertTrue(Store.d.moments.isEmpty())

        // 3. отправка ДЗ: через Entries (как из уведомления) → воркер → пакет HW
        val math = Store.d.subjects.first { it.name == "Математика" }
        val e = Entries.create(Store.d, math.id, "", LocalDate.now(), 1, "№238")
        Entries.addAndMaybeSend(ctx, e)
        val queued = Store.d.queue.single()
        val cid = queued.clientId
        assertTrue(cid.isNotBlank())
        assertNull(Store.d.outbox.single().state)
        // основной сразу отвечает «в канале»
        Thread {
            Thread.sleep(1500)
            mainSends(Packet(Packet.ACK, "main", to = me, clientId = cid, ack = AckState.SENT))
        }.start()
        val worker = TestWorkerBuilder<HelperSendWorker>(ctx, Executors.newSingleThreadExecutor(), workDataOf("id" to queued.id)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(Store.d.queue.isEmpty())
        val hw = upPackets().single { it.type == Packet.HW }.hw!!
        assertEquals("№238", hw.text)
        assertEquals(math.id, hw.subjectId)
        assertEquals(AckState.SENT, Store.d.outbox.single().state)
        assertTrue(Store.d.isSent(math.id, LocalDate.now().toString()))
        // права от основного доходят
        mainSends(Packet(Packet.PERM, "main", to = me, status = HelperStatus.ACTIVE, perms = listOf(HelperPerm.PHOTOS), name = "Илья"))
        Thread.sleep(250)
        Link.pollNow(ctx)
        assertTrue(HelperLink.can(Store.d, HelperPerm.PHOTOS))
        assertFalse(HelperLink.can(Store.d, HelperPerm.SKIP))

        // 4. отклонение
        mainSends(Packet(Packet.ACK, "main", to = me, clientId = cid, ack = AckState.REJECTED, note = "не тот предмет"))
        Thread.sleep(250)
        Link.pollNow(ctx)
        assertEquals(AckState.REJECTED, Store.d.outbox.single().state)
        assertEquals("не тот предмет", Store.d.outbox.single().note)

        // 5. отключение
        HelperLink.disconnect()
        assertFalse(Store.d.link.active)
        assertEquals(Packet.BYE, upPackets().last().type)
    }
}
