package com.ilya.dzshka

import com.ilya.dzshka.data.*
import com.ilya.dzshka.link.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.concurrent.thread

class LinkTransportTest {
    private val server = System.getProperty("ntfy.url") ?: "http://127.0.0.1:2586"

    private fun serverUp() = runCatching {
        Ntfy.http.newCall(okhttp3.Request.Builder().url("$server/v1/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    @Test fun deriveIsStableAndNormalized() {
        val a = LinkCrypto.derive("Кот 2026")
        val b = LinkCrypto.derive("  кот2026 ")
        assertEquals(a, b)
        assertNotEquals(a.topic, LinkCrypto.derive("кот2027").topic)
        assertTrue(a.topic.matches(Regex("[a-z0-9]{26}")))
    }

    @Test fun encryptRoundTripAndWrongKey() {
        val s = LinkCrypto.derive("секрет123")
        val enc = LinkCrypto.encrypt(s.keyB64, "привет".toByteArray())
        assertEquals("привет", LinkCrypto.decrypt(s.keyB64, enc)!!.decodeToString())
        assertNull(LinkCrypto.decrypt(LinkCrypto.derive("другой").keyB64, enc))
    }

    private fun bigCfg(): HelperCfg {
        val d = Sched.importText(AppData(), (1..5).joinToString("\n") { "День$it" } + "\n" +
            "Пн: " + (1..8).joinToString(", ") { "Предмет номер $it с длинным названием" }, 0).data
        return Proto.cfgFrom(d.copy(holidays = (1..60).map { Holiday(name = "Каникулы $it " + java.util.UUID.randomUUID() + java.util.UUID.randomUUID(), from = "2026-11-01", to = "2026-11-02") }))
    }

    @Test fun chunkedPacketThroughNtfy() {
        assumeTrue("локальный ntfy не запущен", serverUp())
        val s = LinkCrypto.derive("тест-${System.nanoTime()}")
        val cfg = bigCfg()
        val parts = Envelope.pack(s.keyB64, Proto.encode(Packet(Packet.CFG, "main1", cfg = cfg)))
        assertTrue("ожидаем несколько кусков, было ${parts.size}", parts.size > 1)
        parts.forEach { Ntfy.publish(server, Proto.downTopic(s.topic), it) }
        // ntfy.sh пишет кэш пачками — сообщения появляются в выдаче через 1–3 секунды
        var msgs = emptyList<NtfyMsg>()
        val t0 = System.currentTimeMillis()
        while (msgs.size < parts.size && System.currentTimeMillis() - t0 < 10_000) { Thread.sleep(500); msgs = Ntfy.poll(server, Proto.downTopic(s.topic), "all") }
        assertEquals(parts.size, msgs.size)
        var got: Packet? = null
        msgs.forEach { m -> Envelope.feed(s.keyB64, m.message)?.let { got = Proto.decode(it) } }
        assertEquals(cfg, got!!.cfg)
        // чужой ключ ничего не собирает
        val other = LinkCrypto.derive("чужой")
        assertTrue(msgs.mapNotNull { Envelope.feed(other.keyB64, it.message) }.isEmpty())
        // since=id не отдаёт сам этот id (но ntfy.sh может повторить сообщения той же секунды — их отсекает Link.process)
        assertTrue(Ntfy.poll(server, Proto.downTopic(s.topic), msgs.last().id).none { it.id == msgs.last().id })
    }

    @Test fun streamReceivesLive() {
        assumeTrue("локальный ntfy не запущен", serverUp())
        val s = LinkCrypto.derive("стрим-${System.nanoTime()}")
        val topic = Proto.upTopic(s.topic)
        val got = java.util.concurrent.CopyOnWriteArrayList<String>()
        var alive = true
        val t = thread { runCatching { Ntfy.stream(server, topic, "all", { alive }) { m -> Envelope.feed(s.keyB64, m.message)?.let(got::add) } } }
        Thread.sleep(500)
        Envelope.pack(s.keyB64, Proto.encode(Packet(Packet.HELLO, "h1", name = "Петя"))).forEach { Ntfy.publish(server, topic, it) }
        val t0 = System.currentTimeMillis()
        while (got.isEmpty() && System.currentTimeMillis() - t0 < 5000) Thread.sleep(50)
        alive = false
        assertEquals("Петя", Proto.decode(got.single())!!.name)
        t.join(8000)
    }

    @Test fun cfgApplyKeepsHelperOwnStuff() {
        val main = Sched.importText(AppData(), "Пн: Математика, Физика", 0).data.copy(viber = ViberCfg(token = "SECRET", channelName = "ДЗ 7-Б"))
        val cfg = Proto.cfgFrom(main)
        assertFalse(Proto.json.encodeToString(HelperCfg.serializer(), cfg).contains("SECRET"))
        val helper = AppData(look = LookCfg(theme = ThemeMode.DARK), link = LinkCfg(myName = "Петя", keyB64 = "k", topic = "t"))
        val after = Proto.applyCfg(helper, cfg)
        assertEquals(main.subjects, after.subjects)
        assertEquals(ThemeMode.DARK, after.look.theme)
        assertEquals("Петя", after.link.myName)
        assertEquals("ДЗ 7-Б", after.viber.channelName)
        assertEquals("", after.viber.token)
        assertTrue(after.moments.isEmpty())
        assertEquals(cfg.ver, Proto.cfgFrom(main).ver)
        assertNotEquals(cfg.ver, Proto.cfgFrom(main.copy(firstLessonNum = 0)).ver)
    }
}
