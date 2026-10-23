package com.ilya.dzshka

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.ilya.dzshka.data.*
import com.ilya.dzshka.ui.PopupActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PopupTest {
    private fun shot(a: android.app.Activity, name: String) {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val v = a.window.decorView
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File(System.getProperty("shots.dir") ?: "shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun popup() {
        org.junit.Assume.assumeTrue(!BuildConfig.HELPER)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Store.init(ctx)
        val d = Sched.importText(AppData(setupDone = true, link = LinkCfg(askLater = true)), (listOf("Пн", "Вт", "Ср", "Чт", "Пт").joinToString("\n") { "$it: Математика, Физика" }), 0).data
        Store.replaceAll(d.copy(subjects = d.subjects.map { if (it.name == "Математика") it.copy(emoji = "📐") else it },
            reminder = d.reminder.copy(extended = true)))
        val math = Store.d.subjects.first { it.name == "Математика" }
        val ctrl = Robolectric.buildActivity(PopupActivity::class.java, PopupActivity.intent(ctx, LocalDate.of(2026, 10, 19), 1, 2, Store.d.subjects.first { it.name == "Физика" }.id, 5)).setup()
        Thread.sleep(400)
        shot(ctrl.get(), "50_popup")
        // режим «Написать»
        val root = ctrl.get().window.decorView
        @Suppress("UNUSED_VARIABLE") val unused = root
    }
}
