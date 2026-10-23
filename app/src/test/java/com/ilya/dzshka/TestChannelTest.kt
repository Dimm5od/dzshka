package com.ilya.dzshka

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import androidx.work.workDataOf
import com.ilya.dzshka.data.*
import com.ilya.dzshka.work.Sender
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.util.concurrent.Executors

/** «🧪 в тест-канал»: всё уходит во второй канал, очередь и отметки «отправлено» не меняются. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestChannelTest {
    private val srv = MockWebServer()
    private val calls = mutableListOf<Pair<String, String>>() // токен → тело

    @Before fun up() {
        srv.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (path.startsWith("/up")) return MockResponse().setBody("https://img.example/p1.jpeg")
                synchronized(calls) { calls += (request.getHeader("X-Viber-Auth-Token") ?: "") to request.body.readUtf8() }
                return MockResponse().setBody("""{"status":0,"status_message":"ok","message_token":${calls.size}}""")
            }
        }
        srv.start()
        System.setProperty("viber.url", srv.url("/pa/").toString())
    }

    @After fun down() { srv.shutdown() }

    @Test fun sendsOnlyToTestChannel() {
        assumeTrue(!BuildConfig.HELPER)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        Store.init(ctx)
        val base = Sched.importText(AppData(), "Пн: Математика\nВт: Математика\nСр: Математика\nЧт: Математика\nПт: Математика\nСб: Математика\nВс: Математика", 0).data
        val math = base.subjects.first()
        File(Store.photosDir(), "t1.jpeg").writeBytes(ByteArray(2000) { 1 })
        val e = HwEntry(subjectId = math.id, subjectName = math.name, text = "№ 5", photos = listOf("t1.jpeg"),
            lessonDate = LocalDate.now().toString(), due = LocalDate.now().plusDays(1).toString())
        Store.replaceAll(base.copy(
            viber = ViberCfg(token = "MAIN", fromId = "m"),
            testViber = ViberCfg(token = "TEST", fromId = "t", channelName = "Тест"),
            photo = PhotoCfg(hosting = Hosting.CUSTOM, customUrl = srv.url("/up").toString()),
            queue = listOf(e),
        ))
        Sender.sendTest(ctx, Store.d.queue, Studio.queueMoment(Store.d).id)
        val copy = Store.d.testQueue.single()
        assertNotEquals(e.id, copy.id)
        val w = TestWorkerBuilder<Sender.SendWorker>(ctx, Executors.newSingleThreadExecutor(),
            workDataOf("ids" to arrayOf(copy.id), "moment" to Studio.queueMoment(Store.d).id, "test" to true)).build()
        val res = w.doWork()
        assertTrue(res.toString(), res is ListenableWorker.Result.Success)

        assertTrue(calls.isNotEmpty())
        assertTrue("всё ушло в тест-канал", calls.all { it.first == "TEST" })
        assertTrue(calls.any { it.second.contains("\"picture\"") && it.second.contains("img.example") })
        // основная очередь не тронута, отметок нет, копии убраны, фото основной записи на месте
        assertEquals(listOf(e.id), Store.d.queue.map { it.id })
        assertEquals(EntryStatus.QUEUED, Store.d.queue.single().status)
        assertTrue(Store.d.sentKeys.isEmpty())
        assertTrue(Store.d.channelPosts.isEmpty())
        assertTrue(Store.d.testQueue.isEmpty())
        assertTrue(File(Store.photosDir(), "t1.jpeg").exists())
        assertTrue(Store.d.history.first().text.startsWith("🧪"))
    }

    @Test fun realHwSendsTextToMainChannel() {
        assumeTrue(!BuildConfig.HELPER)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        Store.init(ctx)
        val base = Sched.importText(AppData(), "Пн: Математика\nВт: Математика\nСр: Математика\nЧт: Математика\nПт: Математика\nСб: Математика\nВс: Математика", 0).data
        val math = base.subjects.first()
        val e = HwEntry(subjectId = math.id, subjectName = math.name, text = "№ 5, 6",
            lessonDate = LocalDate.now().toString(), due = LocalDate.now().plusDays(1).toString(), status = EntryStatus.SENDING)
        Store.replaceAll(base.copy(viber = ViberCfg(token = "MAIN", fromId = "m"), queue = listOf(e)))
        val w = TestWorkerBuilder<Sender.SendWorker>(ctx, Executors.newSingleThreadExecutor(),
            workDataOf("ids" to arrayOf(e.id), "moment" to Studio.queueMoment(Store.d).id)).build()
        val res = w.doWork()
        assertTrue(res.toString(), res is ListenableWorker.Result.Success)
        assertTrue(calls.isNotEmpty())
        assertTrue("ушло в основной канал", calls.all { it.first == "MAIN" })
        assertTrue(calls.any { it.second.contains("№ 5, 6") })
        assertTrue("очередь очищена", Store.d.queue.isEmpty())
        assertTrue("отмечено отправленным", Store.d.isSent(math.id, LocalDate.now().toString()))
        assertTrue("в архиве", Store.d.archive.any { it.text == "№ 5, 6" })
    }
}
