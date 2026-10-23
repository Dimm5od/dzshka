package com.ilya.dzshka

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.ilya.dzshka.data.AppData
import com.ilya.dzshka.data.Store
import com.ilya.dzshka.mascot.CatService
import com.ilya.dzshka.mascot.CatWidget
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSettings
import java.time.Duration

/** Кот выходит на экран, гуляет, реагирует и уходит домой — без падений. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatServiceTest {
    @Test fun walkAndGoHome() {
        assumeTrue(!BuildConfig.HELPER)
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Store.init(ctx); Store.replaceAll(AppData())
        ShadowSettings.setCanDrawOverlays(true)
        val c = Robolectric.buildService(CatService::class.java, Intent(ctx, CatService::class.java).setAction(CatService.ACTION_OUT)).create()
        c.startCommand(0, 1)
        assertTrue(CatService.running)
        assertTrue(Store.d.mascot.out)
        ShadowLooper.idleMainLooper(30, java.util.concurrent.TimeUnit.SECONDS)   // гуляет, моргает, говорит
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        val views = (org.robolectric.shadow.api.Shadow.extract<Any>(wm) as org.robolectric.shadows.ShadowWindowManagerImpl).views
        assertEquals(1, views.size)
        val v = views[0]
        fun ev(a: Int, x: Float, y: Float) = android.view.MotionEvent.obtain(0, 0, a, x, y, 0).also { it.setLocation(x, y) }
        // погладить 7 раз (с паузами, чтобы не было «двойного нажатия»)
        repeat(7) {
            v.dispatchTouchEvent(ev(0, 10f, 10f)); v.dispatchTouchEvent(ev(1, 10f, 10f))
            ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        // перетащить
        v.dispatchTouchEvent(ev(0, 10f, 10f)); v.dispatchTouchEvent(ev(2, 200f, 300f)); v.dispatchTouchEvent(ev(1, 200f, 300f))
        ShadowLooper.idleMainLooper(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(CatService.running)
        CatWidget.refresh(ctx)
        c.get().onStartCommand(Intent(ctx, CatService::class.java).setAction(CatService.ACTION_HOME), 0, 2)
        ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse(CatService.running)
        assertFalse(Store.d.mascot.out)
    }
}
