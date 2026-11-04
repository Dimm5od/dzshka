package com.ilya.dzshka.mascot

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.ilya.dzshka.MainActivity
import com.ilya.dzshka.R
import com.ilya.dzshka.data.*
import com.ilya.dzshka.ui.NowState
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Виджет «Сейчас»: какой идёт урок или перемена, сколько осталось, что дальше — как карточка в «Расписании».
 * Во время учебного дня обновляется каждую минуту (не будя телефон), в остальное время — в полночь.
 * Имя класса прежнее, чтобы уже поставленный виджет не пропал.
 */
class CatWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        refresh(ctx)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        Store.init(ctx)
        refresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_TICK) {
            Store.init(ctx)
            refresh(ctx)
        }
    }

    companion object {
        const val ACTION_TICK = "com.ilya.dzshka.WIDGET_TICK"

        /** Обновить виджеты и уведомление на экране блокировки, назначить следующее обновление. */
        fun refresh(ctx: Context) {
            if (com.ilya.dzshka.BuildConfig.HELPER) return
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr?.getAppWidgetIds(ComponentName(ctx, CatWidget::class.java)) ?: IntArray(0)
            val d = Store.d
            val now = LocalDateTime.now()
            val i = NowState.compute(d, now)
            if (ids.isNotEmpty()) push(ctx, mgr!!, ids, d, i, now)
            com.ilya.dzshka.work.NowNotif.update(ctx, d, i, now)
            if (ids.isNotEmpty() || d.widget.lockNotif) scheduleTick(ctx, i.live, now)
        }

        private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 3,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun tickIntent(ctx: Context) = PendingIntent.getBroadcast(ctx, 4,
            Intent(ctx, CatWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private fun RemoteViews.prog(p: Float?) {
            setViewVisibility(R.id.prog, if (p == null) View.GONE else View.VISIBLE)
            if (p != null) setProgressBar(R.id.prog, 1000, (p.coerceIn(0f, 1f) * 1000).toInt(), false)
        }

        private fun small(ctx: Context, i: NowState.Info): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_now_small).apply {
                setTextViewText(R.id.line1, i.short1)
                setTextViewText(R.id.line2, i.short2)
                setViewVisibility(R.id.line2, if (i.short2.isBlank()) View.GONE else View.VISIBLE)
                prog(i.prog)
                setOnClickPendingIntent(R.id.root, open(ctx))
            }

        /** Полоса в одну клетку высотой: заголовок и строка под ним. */
        private fun row(ctx: Context, i: NowState.Info): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_now_small).apply {
                setTextViewText(R.id.line1, i.big)
                setTextViewText(R.id.line2, i.line)
                setViewVisibility(R.id.line2, if (i.line.isBlank()) View.GONE else View.VISIBLE)
                prog(i.prog)
                setOnClickPendingIntent(R.id.root, open(ctx))
            }

        private fun full(ctx: Context, i: NowState.Info, big: Boolean): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_now).apply {
                setTextViewText(R.id.big, i.big)
                setTextViewText(R.id.small, i.small)
                setViewVisibility(R.id.small, if (i.small.isBlank()) View.GONE else View.VISIBLE)
                setTextViewText(R.id.next, i.next)
                setViewVisibility(R.id.next, if (i.next.isBlank()) View.GONE else View.VISIBLE)
                setInt(R.id.small, "setMaxLines", if (big) 3 else 2)
                setInt(R.id.next, "setMaxLines", if (big) 2 else 1)
                prog(i.prog)
                setOnClickPendingIntent(R.id.root, open(ctx))
            }

        /** Вид под размер (для картинок в тестах и старых Android). */
        fun build(ctx: Context, d: AppData, wDp: Float, hDp: Float, now: LocalDateTime = LocalDateTime.now()): RemoteViews {
            val i = NowState.compute(d, now)
            return when {
                hDp < 100 && wDp < 160 -> small(ctx, i)
                hDp < 100 -> row(ctx, i)
                else -> full(ctx, i, big = hDp >= 180)
            }
        }

        private fun push(ctx: Context, mgr: AppWidgetManager, ids: IntArray, d: AppData, i: NowState.Info, now: LocalDateTime) {
            for (id in ids) {
                val views = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
                    SizeF(40f, 40f) to small(ctx, i),
                    SizeF(160f, 40f) to row(ctx, i),
                    SizeF(160f, 100f) to full(ctx, i, big = false),
                    SizeF(160f, 180f) to full(ctx, i, big = true),
                )) else {
                    val o = mgr.getAppWidgetOptions(id)
                    build(ctx, d, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).toFloat(),
                        o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 120).toFloat(), now)
                }
                runCatching { mgr.updateAppWidget(id, views) }
            }
        }

        /** Следующее обновление: в учебный день — в начале следующей минуты, иначе — после полуночи. */
        internal fun nextTick(live: Boolean, now: LocalDateTime): LocalDateTime =
            if (live) now.withSecond(0).withNano(0).plusMinutes(1)
            else now.toLocalDate().plusDays(1).atStartOfDay().plusSeconds(5)

        private fun scheduleTick(ctx: Context, live: Boolean, now: LocalDateTime) {
            val ms = nextTick(live, now).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = tickIntent(ctx)
            // RTC без _WAKEUP: телефон не будим; пока экран выключен — сработает сразу при включении
            runCatching {
                if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExact(AlarmManager.RTC, ms, pi)
                else am.setWindow(AlarmManager.RTC, ms, 30_000, pi)
            }.onFailure { runCatching { am.set(AlarmManager.RTC, ms, pi) } }
        }
    }
}
