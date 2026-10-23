package com.ilya.dzshka

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.ilya.dzshka.data.*
import com.ilya.dzshka.ui.DzTheme
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1500dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val dir = File(System.getProperty("shots.dir") ?: "shots").apply { mkdirs() }

    private fun shot(name: String) {
        rule.waitForIdle()
        val v = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { v.draw(Canvas(bmp)) }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sampleData(setupDone: Boolean): AppData {
        val txt = """
            Пн: Математика, Русский язык, Литература, Физика, История
            Вт: Алгебра, Геометрия, Английский язык, Химия, Физкультура
            Ср: Математика, Биология, География, Русский язык, Информатика
            Чт: Физика, Литература, Английский язык, История, Музыка
            Пт: Математика, Химия, Русский язык, Физкультура, Технология, Английский язык
        """.trimIndent()
        val d0 = Sched.importText(AppData(), txt, 0).data
        val math = d0.subjects.first { it.name == "Математика" }
        val chem = d0.subjects.first { it.name == "Химия" }
        val subjects = d0.subjects.map {
            when (it.name) {
                "Математика" -> it.copy(emoji = "📐", short = "Матем")
                "Химия" -> it.copy(emoji = "🧪")
                "Русский язык" -> it.copy(emoji = "📝", short = "Рус")
                else -> it
            }
        }
        val today = LocalDate.now()
        val d1 = d0.copy(subjects = subjects, setupDone = setupDone)
        val q = listOf(
            HwEntry(subjectId = math.id, subjectName = math.name, text = "№238, №240 (а, б)", lessonDate = today.toString(), lessonNum = 1,
                due = Sched.dueFor(d1, DueMode.NEXT_LESSON, math.id, today).toString()),
            HwEntry(subjectId = chem.id, subjectName = chem.name, text = "§12, вопросы 1–4", lessonDate = today.toString(), lessonNum = 2,
                due = Sched.dueFor(d1, DueMode.NEXT_LESSON, chem.id, today).toString(), status = EntryStatus.FAILED,
                error = "Нет связи с интернетом"),
        )
        val tl = Sched.lessonsOn(d1, today)
        val k0 = tl.getOrNull(0)?.let { "${it.date}|${it.subjectId}" }
        val k1 = tl.getOrNull(1)?.let { "${it.date}|${it.subjectId}" }
        return d1.copy(
            sentKeys = listOfNotNull(k0),
            hwLog = listOfNotNull(k0?.let { it to "стр. 45, упр. 3 и выучить правило" }).toMap(),
            skipped = listOfNotNull(k1),
            link = LinkCfg(askLater = true),
            queue = q,
            viber = ViberCfg(token = "abc", fromId = "id1", fromName = "Илья", channelName = "ДЗ 7-Б"),
            moments = Presets.list.last().build(),
        )
    }

    @Before fun setUp() {
        Store.init(ApplicationProvider.getApplicationContext())
    }

    @Test fun mainFlow() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        Store.replaceAll(sampleData(true))
        val incoming = mutableStateOf<Route?>(null)
        rule.setContent { DzTheme(Store.d.look) { AppRoot(incoming) } }
        shot("01_home")

        rule.onNodeWithText("Расписание").performClick()
        shot("02_schedule")

        rule.onNodeWithTag("studio").performClick()
        rule.mainClock.advanceTimeBy(1500)
        shot("03a_studio")
        Store.update { it.copy(moments = Presets.list.first().build()) }
        shot("03b_studio_default")
        rule.onNodeWithText("Напоминания").performClick()
        rule.mainClock.advanceTimeBy(800)
        shot("03c_studio_remind")
        rule.onNodeWithText("Тест").performClick()
        rule.mainClock.advanceTimeBy(800)
        shot("03d_studio_test")
        rule.onNodeWithTag("studio").performClick()   // выход из Студии
        rule.mainClock.advanceTimeBy(1500)

        rule.onNodeWithText("Настройки").performClick()
        rule.onNodeWithText("Токены и названия").performClick()
        shot("03_templates")
        rule.onNodeWithContentDescription("Назад").performClick()
        Store.update { it.copy(moments = Presets.list.last().build()) }
        shot("04_settings")

        rule.onNodeWithText("Главная").performClick()
        rule.onNodeWithTag("fab").performClick()
        shot("05_entry_new")
        rule.onNodeWithText("Что задали").performTextInput("стр. 45, упр. 3")
        rule.onNodeWithText("выучить").performClick()
        shot("06_entry_typed")
    }

    @Test fun wizard() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        Store.replaceAll(AppData())
        val incoming = mutableStateOf<Route?>(null)
        rule.setContent { DzTheme(Store.d.look) { AppRoot(incoming) } }
        shot("10_wizard_0")
        rule.onNodeWithText("Далее").performClick()
        shot("11_wizard_1")
        rule.onNodeWithText("Далее").performClick()
        rule.onNodeWithText("Далее").performClick()
        shot("12_wizard_3")
    }

    @Test fun editQueued() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        Store.replaceAll(sampleData(true))
        val id = Store.d.queue[1].id
        val incoming = mutableStateOf<Route?>(Route.Entry(editId = id))
        rule.setContent { DzTheme(Store.d.look) { AppRoot(incoming) } }
        shot("07_entry_edit")
    }

    @Test fun weekTest() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        Store.replaceAll(sampleData(true).copy(moments = Presets.list.first().build(), queue = emptyList()))
        val incoming = mutableStateOf<Route?>(Route.Test)
        rule.setContent { DzTheme(Store.d.look) { AppRoot(incoming) } }
        shot("20_test_idle")
        com.ilya.dzshka.work.TestRun.start(rule.activity, com.ilya.dzshka.work.TestRun.Cfg(minutes = 1, notifications = false))
        Thread.sleep(9000)
        rule.waitForIdle()
        shot("21_test_running")
        com.ilya.dzshka.work.TestRun.stop(rule.activity)
        // быстрый полный прогон: 1 минута, отвечаем «придумай сам» на каждый звонок
        com.ilya.dzshka.work.TestRun.reset()
        com.ilya.dzshka.work.TestRun.start(rule.activity, com.ilya.dzshka.work.TestRun.Cfg(minutes = 1, notifications = false))
        val t0 = System.currentTimeMillis()
        while (com.ilya.dzshka.work.TestRun.state.value.running && System.currentTimeMillis() - t0 < 90_000) {
            com.ilya.dzshka.work.TestRun.answer(com.ilya.dzshka.work.TestRun.Answer.Auto)
            Thread.sleep(150)
        }
        val fin = com.ilya.dzshka.work.TestRun.state.value
        org.junit.Assert.assertTrue("тест не закончился", fin.finished)
        org.junit.Assert.assertTrue(fin.entries > 10)
        org.junit.Assert.assertTrue(fin.warnings.isEmpty())
        rule.waitForIdle()
        shot("22_test_finished")
    }

    @Test fun darkTheme() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        Store.replaceAll(sampleData(true).copy(look = LookCfg(theme = ThemeMode.DARK, accent = 0xFF7C4DFF)))
        val incoming = mutableStateOf<Route?>(null)
        rule.setContent {
            DzTheme(Store.d.look, space = true) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    com.ilya.dzshka.ui.SpaceBackdrop()
                    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { AppRoot(incoming) }
                }
            }
        }
        rule.mainClock.advanceTimeBy(2000)
        shot("08_home_dark")
        rule.onNodeWithText("Расписание").performClick()
        rule.mainClock.advanceTimeBy(800)
        shot("09_schedule_dark")
        rule.onNodeWithTag("studio").performClick()
        rule.mainClock.advanceTimeBy(1500)
        shot("09b_studio_dark")
    }

    @Test fun mainNewScreens() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        val base = sampleData(true)
        val math = base.subjects.first { it.name == "Математика" }
        Store.replaceAll(base.copy(
            link = LinkCfg(keyB64 = "k", topic = "t", myName = "Илья", lastContact = System.currentTimeMillis() - 60_000),
            helpers = listOf(
                Helper(id = "a", name = "Петя", model = "Xiaomi Redmi Note 12", version = "1.0.0-helper", status = HelperStatus.ACTIVE, sent = 12, rejected = 1),
                Helper(id = "b", name = "Маша", model = "Samsung A54", version = "1.0.0-helper", status = HelperStatus.PENDING),
                Helper(id = "c", name = "Вася", model = "iPhone?", version = "1.0.0-helper", status = HelperStatus.BLOCKED, allowedSubjects = listOf(math.id)),
            ),
            queue = base.queue + HwEntry(subjectId = math.id, subjectName = "Математика", text = "№ 240, 241", author = "Петя", helperId = "a",
                lessonDate = LocalDate.now().toString(), due = LocalDate.now().plusDays(1).toString(), status = EntryStatus.APPROVAL),
            channelPosts = listOf(
                ChannelPost(token = "1", text = "📅 ДЗ за 19 октября (понедельник)"),
                ChannelPost(token = "2", text = "📐 Математика: №238, №240 (а, б)", author = "Петя"),
                ChannelPost(token = "3", text = "🧪 Химия — ДЗ на 20 октября", photoUrl = "https://files.catbox.moe/x.jpeg"),
            ),
        ))
        val incoming = mutableStateOf<Route?>(null)
        rule.setContent { DzTheme(Store.d.look) { AppRoot(incoming) } }
        shot("30_home_helpers")
        incoming.value = Route.Channel
        rule.waitForIdle()
        shot("31_channel")
        rule.onNodeWithContentDescription("Назад").performClick()
        incoming.value = Route.Helpers
        rule.waitForIdle()
        shot("32_helpers")
        incoming.value = Route.LinkSetup
        rule.waitForIdle()
        shot("33_link_setup")
    }

    @Test fun helperScreens() {
        org.junit.Assume.assumeTrue(BuildConfig.HELPER)
        Store.replaceAll(AppData())
        val incoming = mutableStateOf<Route?>(null)
        rule.setContent { DzTheme(Store.d.look) { AppRoot(incoming) } }
        shot("40_helper_connect")
        val main = sampleData(true)
        val cfg = com.ilya.dzshka.link.Proto.cfgFrom(main.copy(link = LinkCfg(myName = "Илья")))
        Store.replaceAll(com.ilya.dzshka.link.Proto.applyCfg(AppData(link = LinkCfg(keyB64 = "k", topic = "t", myName = "Петя", myStatus = HelperStatus.ACTIVE, lastContact = System.currentTimeMillis())), cfg).copy(
            outbox = listOf(
                HelperOut(clientId = "1", subjectName = "Математика", text = "№238", state = com.ilya.dzshka.link.AckState.SENT),
                HelperOut(clientId = "2", subjectName = "Химия", text = "§12", state = com.ilya.dzshka.link.AckState.PENDING),
                HelperOut(clientId = "3", subjectName = "Физика", text = "упр. 5", state = com.ilya.dzshka.link.AckState.REJECTED, note = "по этому предмету тебе присылать нельзя"),
            ),
        ))
        rule.waitForIdle()
        shot("41_helper_home")
        rule.onNodeWithText("Расписание").performClick()
        shot("42_helper_schedule")
        rule.onNodeWithText("Настройки").performClick()
        shot("43_helper_settings")
        rule.onNodeWithText("Главная").performClick()
        rule.onAllNodesWithText("ДЗ", useUnmergedTree = true).onLast().performClick()
        shot("44_helper_entry")
    }

    @Test fun updateSection() {
        Store.replaceAll(AppData())
        rule.setContent { DzTheme(Store.d.look) { androidx.compose.foundation.layout.Column { com.ilya.dzshka.ui.UpdateSection() } } }
        shot("50_update")
    }

    @Test fun lessonInfoAndTestChannel() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        val base = sampleData(true)
        val k0 = base.sentKeys.firstOrNull() ?: return
        Store.replaceAll(base.copy(
            queue = base.queue.filterNot { it.subjectId == k0.substringAfter("|") } +
                HwEntry(subjectId = "zz", subjectName = "Музыка", text = "песня", lessonDate = LocalDate.now().toString()),
            testViber = ViberCfg(token = "t", fromId = "x", channelName = "ДЗ тест"),
            hwInfo = mapOf(k0 to HwInfo(text = "стр. 45, упр. 3 и выучить правило", due = LocalDate.now().plusDays(2).toString(), author = "Петя", photos = 2)),
        ))
        val incoming = mutableStateOf<Route?>(null)
        rule.setContent { DzTheme(Store.d.look) { AppRoot(incoming) } }
        val name = Store.d.subject(k0.substringAfter("|"))!!.name
        rule.onAllNodesWithText(name, substring = true).onFirst().performClick()
        shot("60_lesson_info")
        rule.onNodeWithText("✍ Записал: Петя").assertExists()
        rule.onNodeWithText("📷 фото: 2").assertExists()
        rule.onNodeWithText("⏭ Следующий урок", substring = true).assertExists()
        rule.onNodeWithText("✓ Отправлено в канал", substring = true).assertExists()
        rule.onNodeWithText("Закрыть").performClick()
        rule.onAllNodesWithText("🧪", substring = true).onFirst().performClick()
        shot("61_home_test_check")
        incoming.value = Route.Entry(subjectId = k0.substringAfter("|"), date = LocalDate.now().toString(), num = 1)
        rule.waitForIdle()
        rule.onNodeWithText("Что задали").performTextInput("тест фото")
        rule.onAllNodesWithText("🧪", substring = true).onFirst().performScrollTo().performClick()
        shot("62_entry_test")
    }

    @Test fun hwTabAndCat() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        val base = sampleData(true)
        val today = LocalDate.now()
        fun sid(n: String) = base.subjects.first { it.name == n }.id
        val recs = listOf(
            HwRecord(id = "a", subjectId = sid("Математика"), subjectName = "Математика", text = "№238, 240", lessonDate = today.minusDays(2).toString(), due = today.toString()),
            HwRecord(id = "b", subjectId = sid("Химия"), subjectName = "Химия", text = "§12, вопросы 1–4", lessonDate = today.minusDays(1).toString(), due = today.plusDays(1).toString(), author = "Петя"),
            HwRecord(id = "c", subjectId = sid("Русский язык"), subjectName = "Русский язык", text = "упр. 112", lessonDate = today.minusDays(3).toString(), due = today.minusDays(1).toString(), source = "chat"),
            HwRecord(id = "d", subjectId = sid("Математика"), subjectName = "Математика", text = "стр. 45, задачи 3–5", lessonDate = today.toString(), due = today.plusDays(1).toString(), photos = listOf("https://x/1.jpeg")),
            HwRecord(id = "e", subjectId = sid("Физкультура"), subjectName = "Физкультура", none = true, lessonDate = today.minusDays(5).toString(), due = today.minusDays(4).toString()),
            HwRecord(id = "f", subjectId = sid("Математика"), subjectName = "Математика", text = "повторить дроби", lessonDate = today.minusDays(9).toString(), due = today.minusDays(7).toString()),
        )
        Store.replaceAll(base.copy(archive = recs, queue = emptyList(), gh = GhCfg(enabled = false)))
        val incoming = mutableStateOf<Route?>(null)
        rule.setContent { DzTheme(Store.d.look) { AppRoot(incoming) } }
        rule.onAllNodesWithText("ДЗ", useUnmergedTree = true).fetchSemanticsNodes() // есть вкладка
        incoming.value = Route.HwFor(sid("Математика"))
        rule.waitForIdle()
        shot("70_hw_math")
        assertTrue(rule.onAllNodesWithText("№238, 240", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithContentDescription("Назад").performClick()
        incoming.value = Route.HwFor("")
        rule.waitForIdle()
        shot("71_hw_all")
        rule.onNodeWithContentDescription("Назад").performClick()
        rule.onNodeWithText("Настройки").performClick()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("📱 Виджет ДЗ"))
        shot("72_settings_cat")
    }

    @Test fun widgetRender() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        val base = sampleData(true)
        val today = LocalDate.now()
        val next = Sched.nextSchoolDay(base, today)
        fun sid(n: String) = base.subjects.first { it.name == n }.id
        Store.replaceAll(base.copy(queue = emptyList(), archive = listOf(
            HwRecord(id = "a", subjectId = sid("Математика"), subjectName = "Математика", text = "№238, 240", lessonDate = today.toString(), due = next.toString()),
            HwRecord(id = "b", subjectId = sid("Химия"), subjectName = "Химия", text = "§12, вопросы 1–4", lessonDate = today.toString(), due = next.toString()),
            HwRecord(id = "c", subjectId = sid("Русский язык"), subjectName = "Русский язык", text = "упр. 112", lessonDate = today.minusDays(2).toString(), due = today.toString()),
        )))
        val ctx = rule.activity
        val dens = ctx.resources.displayMetrics.density
        val sizes = listOf(70f to 70f, 250f to 70f, 250f to 140f, 300f to 300f)
        val W = (sizes.maxOf { it.first } * dens).toInt() + 20; val H = sizes.sumOf { (it.second * dens).toInt() + 10 }
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out).apply { drawColor(0xFF6B8FC7.toInt()) }
        rule.runOnUiThread {
            var y = 0
            for ((w, h) in sizes) {
                val rv = com.ilya.dzshka.mascot.CatWidget.build(ctx, Store.d, w, h)
                val v = rv.apply(ctx, android.widget.FrameLayout(ctx))
                val pw = (w * dens).toInt(); val ph = (h * dens).toInt()
                v.measure(android.view.View.MeasureSpec.makeMeasureSpec(pw, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(ph, android.view.View.MeasureSpec.EXACTLY))
                v.layout(0, 0, pw, ph)
                canvas.save(); canvas.translate(10f, y.toFloat()); v.draw(canvas); canvas.restore()
                y += ph + 10
            }
            File(dir, "73_widget.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun newLayout() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        Store.replaceAll(sampleData(true).copy(channelPosts = listOf(ChannelPost(token = "1", text = "📅 ДЗ на завтра\nМатематика: №5"))))
        val incoming = mutableStateOf<Route?>(null)
        rule.setContent { DzTheme(Store.d.look) { AppRoot(incoming) } }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Поиск"))
        shot("80_home_bottom")
        rule.onNodeWithText("Поиск").performClick()
        rule.waitForIdle()
        shot("81_browser_start")
        rule.onNodeWithContentDescription("Закрыть").performClick()
        rule.onNodeWithText("Расписание").performClick()
        rule.mainClock.advanceTimeBy(800)
        shot("82_schedule_now")
        rule.onNodeWithText("ДЗ").performClick()
        rule.mainClock.advanceTimeBy(800)
        shot("83_hw_tab")
    }
}
