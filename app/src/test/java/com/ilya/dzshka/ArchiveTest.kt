package com.ilya.dzshka

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.ilya.dzshka.data.*
import com.ilya.dzshka.mascot.Mascot
import com.ilya.dzshka.mascot.Phrases
import com.ilya.dzshka.net.GhSync
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ArchiveTest {
    private val srv = MockWebServer()
    private var file: String? = null   // содержимое файла на «GitHub»
    private var sha = 0
    private val puts = mutableListOf<JsonObject>()
    private var auth: String? = null

    @Before fun up() {
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                auth = request.getHeader("Authorization")
                if (!request.path!!.startsWith("/repos/Dimm5od/dzshka/contents/hw/archive.json")) return MockResponse().setResponseCode(404)
                return when (request.method) {
                    "GET" -> if (file == null) MockResponse().setResponseCode(404).setBody("{}")
                        else MockResponse().setBody(buildJsonObject {
                            put("sha", "s$sha")
                            put("content", Base64.encodeToString(file!!.toByteArray(), Base64.DEFAULT))
                        }.toString())
                    "PUT" -> {
                        val o = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                        val want = if (file == null) null else "s$sha"
                        if (o["sha"]?.jsonPrimitive?.content != want) return MockResponse().setResponseCode(409)
                        puts += o
                        file = String(Base64.decode(o["content"]!!.jsonPrimitive.content, Base64.DEFAULT))
                        sha++
                        MockResponse().setResponseCode(if (want == null) 201 else 200).setBody("{}")
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        srv.start()
        System.setProperty("github.api", srv.url("/").toString().trimEnd('/'))
        Store.init(ApplicationProvider.getApplicationContext())
    }

    @After fun down() { srv.shutdown(); System.clearProperty("github.api") }

    private fun rec(date: String, sid: String, text: String, upd: Long = 1000) =
        HwRecord(id = "$date|$sid", subjectId = sid, subjectName = sid, text = text, lessonDate = date, due = date, updated = upd)

    @Test fun syncWithGithub() {
        Store.replaceAll(AppData(archive = listOf(rec("2026-10-20", "m", "№1")), gh = GhCfg(token = "tok", dirty = true)))
        // 1. файла нет — создаётся
        val r1 = GhSync.sync()
        assertTrue(r1, r1.startsWith("✓ Сохранено"))
        assertEquals("Bearer tok", auth)
        assertNull(puts[0]["sha"])
        assertEquals(listOf("№1"), GhSync.decode(file!!).map { it.text })
        assertFalse(Store.d.gh.dirty)
        // 2. на GitHub появилось чужое, у нас — своё новое: сливается в обе стороны
        file = GhSync.encode(GhSync.decode(file!!) + rec("2026-10-21", "c", "§3"))
        Archive.put(rec("2026-10-22", "r", "упр. 5", upd = 2000))
        GhSync.sync()
        assertEquals(setOf("№1", "§3", "упр. 5"), GhSync.decode(file!!).map { it.text }.toSet())
        assertEquals(setOf("№1", "§3", "упр. 5"), Store.d.archive.map { it.text }.toSet())
        // 3. правка новее побеждает
        file = GhSync.encode(GhSync.decode(file!!).map { if (it.text == "№1") it.copy(text = "№1, 2", updated = 5000) else it })
        GhSync.sync()
        assertEquals("№1, 2", Store.d.archive.first { it.subjectId == "m" }.text)
        // 4. без токена — только читает
        val before = puts.size
        Store.update { it.copy(gh = it.gh.copy(token = "")) }
        Archive.put(rec("2026-10-23", "f", "задачи"))
        val r4 = GhSync.sync()
        assertTrue(r4, r4.contains("вставь токен"))
        assertEquals(before, puts.size)
        assertNull(auth)
    }

    @Test fun seedFromOldData() {
        val base = Sched.importText(AppData(), "Пн: Математика\nВт: Математика\nСр: Математика\nЧт: Математика\nПт: Математика", 0).data
        val m = base.subjects.first()
        val k = "2026-10-19|${m.id}"
        val d = base.copy(sentKeys = listOf(k), hwInfo = mapOf(k to HwInfo(text = "№5", due = "2026-10-20")), skipped = listOf("2026-10-20|${m.id}"))
        val s = Archive.seed(d)
        assertEquals(2, s.size)
        assertEquals("№5", s.first { it.id == k }.text)
        assertTrue(s.first { it.id != k }.none)
    }

    @Test fun mascotLines() {
        val base = Sched.importText(AppData(), "Пн: Математика, Химия\nВт: Математика\nСр: Химия\nЧт: Математика\nПт: Химия", 0).data
        val tomorrow = Sched.nextSchoolDay(base, LocalDate.now())
        val d = base.copy(archive = listOf(HwRecord(id = "x|m", subjectId = "m", subjectName = "Химия", text = "§3", lessonDate = "x", due = tomorrow.toString())),
            holidays = listOf(Holiday(from = LocalDate.now().plusDays(5).toString(), to = LocalDate.now().plusDays(12).toString())))
        val all = listOf(Phrases.morning, Phrases.day, Phrases.evening, Phrases.night, Phrases.hwNone, Phrases.hwFew, Phrases.hwMany, Phrases.hwSubject,
            Phrases.weekendFri, Phrases.weekendSat, Phrases.weekendSun, Phrases.holidaySoon, Phrases.holidayFirst, Phrases.holiday, Phrases.holidayLast,
            Phrases.pet, Phrases.petSpam, Phrases.drag, Phrases.dropped, Phrases.released, Phrases.calledHome, Phrases.sleepyTap, Phrases.hwSent,
            Phrases.noHwGiven, Phrases.random)
        assertTrue("реплик ${all.sumOf { it.size }}", all.sumOf { it.size } >= 300)
        // во все часы недели кот что-то говорит, и все плейсхолдеры заполнены
        val start = LocalDate.now().atStartOfDay()
        for (hh in 0 until 24 * 7 step 3) {
            val t = start.plusHours(hh.toLong())
            val line = Mascot.daily(d, t)
            assertTrue(line.text.isNotBlank())
            assertFalse("$t: ${line.text}", line.text.contains("{"))
        }
        all.flatten().forEach { assertFalse(it, Mascot.fill(it, d, Mascot.ctx(d)).contains("{")) }
        assertTrue(Mascot.hwLine(d).contains("Химия"))
    }
}
