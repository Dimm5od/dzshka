package com.ilya.dzshka.mascot

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
import java.time.LocalDate

/**
 * Виджет ДЗ (без кота): реплика текстом, ДЗ на завтра (сколько и какое), на сегодня, что сегодня задали.
 * Размер — от 1 клетки (только число) до большого. Что показывать — в Настройках → «Виджет».
 * Имя класса прежнее, чтобы уже поставленный виджет не пропал.
 */
class CatWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        Store.init(ctx)
        push(ctx, mgr, ids, null)
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) {
        Store.init(ctx)
        push(ctx, mgr, intArrayOf(id), null)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_NEXT) {
            Store.init(ctx)
            val mgr = AppWidgetManager.getInstance(ctx)
            push(ctx, mgr, mgr.getAppWidgetIds(ComponentName(ctx, CatWidget::class.java)), Mascot.another(Store.d, lastText).text)
        }
    }

    data class Info(
        val next: LocalDate,
        val nextList: List<HwRecord>,
        val dueToday: List<HwRecord>,
        val given: List<HwRecord>,
    )

    companion object {
        const val ACTION_NEXT = "com.ilya.dzshka.CAT_NEXT"
        @Volatile private var lastText: String? = null

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, CatWidget::class.java))
            if (ids.isEmpty()) return
            push(ctx, mgr, ids, null)
        }

        fun info(d: AppData, today: LocalDate = LocalDate.now()): Info {
            val items = Archive.items(d).map { it.rec }.filter { !it.deleted }
            fun day(r: HwRecord) = Sched.parseDate(r.due) ?: Sched.parseDate(r.lessonDate)
            val next = Sched.nextSchoolDay(d, today)
            return Info(
                next = next,
                nextList = items.filter { !it.none && day(it) == next },
                dueToday = items.filter { !it.none && day(it) == today },
                given = items.filter { it.lessonDate == today.toString() },
            )
        }

        private fun name(d: AppData, r: HwRecord): String {
            val s = d.subject(r.subjectId)
            return listOfNotNull(s?.emoji?.takeIf { it.isNotBlank() }, s?.short?.takeIf { it.isNotBlank() } ?: s?.name ?: r.subjectName).joinToString(" ")
        }

        private fun lines(d: AppData, list: List<HwRecord>, empty: String) =
            if (list.isEmpty()) empty else list.joinToString("\n") { "${name(d, it)} — ${Archive.label(d, it)}" }

        private fun nextWord(d: AppData, i: Info, today: LocalDate): String =
            if (i.next == today.plusDays(1)) "завтра" else d.weekdaysShort.getOrElse(i.next.dayOfWeek.value - 1) { "" }

        private fun tiny(ctx: Context, d: AppData, i: Info, today: LocalDate): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_tiny).apply {
                setTextViewText(R.id.count, i.nextList.size.toString())
                setTextViewText(R.id.label, "ДЗ ${nextWord(d, i, today)}")
                setOnClickPendingIntent(R.id.root, open(ctx))
            }

        private fun row(ctx: Context, d: AppData, i: Info, today: LocalDate): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_row).apply {
                setTextViewText(R.id.count, i.nextList.size.toString())
                setTextViewText(R.id.label, "ДЗ на ${nextWord(d, i, today)}" +
                    if (i.nextList.isEmpty()) ": ничего" else ": " + i.nextList.joinToString(", ") { name(d, it) })
                setOnClickPendingIntent(R.id.root, open(ctx))
            }

        private fun full(ctx: Context, d: AppData, i: Info, today: LocalDate, phrase: String, big: Boolean): RemoteViews =
            RemoteViews(ctx.packageName, R.layout.widget_full).apply {
                val c = d.widget
                fun show(id: Int, on: Boolean) = setViewVisibility(id, if (on) View.VISIBLE else View.GONE)
                show(R.id.phrase, c.phrase)
                setTextViewText(R.id.phrase, phrase)
                setInt(R.id.phrase, "setMaxLines", if (big) 4 else 2)
                show(R.id.next_title, c.next); show(R.id.next, c.next)
                setTextViewText(R.id.next_title, "⏭ На ${nextWord(d, i, today)} · ${i.nextList.size}")
                setTextViewText(R.id.next, lines(d, i.nextList, "ничего не записано"))
                setInt(R.id.next, "setMaxLines", if (big) 8 else 3)
                val todayOn = c.dueToday && (big || i.dueToday.isNotEmpty())
                show(R.id.today_title, todayOn); show(R.id.today, todayOn)
                setTextViewText(R.id.today, lines(d, i.dueToday, "ничего"))
                setInt(R.id.today, "setMaxLines", if (big) 6 else 2)
                val givenOn = c.givenToday && (big || i.given.isNotEmpty())
                show(R.id.given_title, givenOn); show(R.id.given, givenOn)
                setTextViewText(R.id.given, lines(d, i.given, "пока ничего"))
                setInt(R.id.given, "setMaxLines", if (big) 6 else 2)
                setOnClickPendingIntent(R.id.root, open(ctx))
                setOnClickPendingIntent(R.id.phrase, PendingIntent.getBroadcast(ctx, 2,
                    Intent(ctx, CatWidget::class.java).setAction(ACTION_NEXT), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }

        private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 3,
            Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_HW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** Вид под размер (для картинок в тестах и старых Android). */
        fun build(ctx: Context, d: AppData, wDp: Float, hDp: Float, phrase: String? = null): RemoteViews {
            val today = LocalDate.now()
            val i = info(d, today)
            val p = phrase ?: Mascot.daily(d).text
            return when {
                hDp < 100 && wDp < 140 -> tiny(ctx, d, i, today)
                hDp < 100 -> row(ctx, d, i, today)
                else -> full(ctx, d, i, today, p, big = hDp >= 220)
            }
        }

        private fun push(ctx: Context, mgr: AppWidgetManager, ids: IntArray, phrase: String?) {
            if (ids.isEmpty()) return
            val d = Store.d
            val p = phrase ?: Mascot.daily(d).text
            lastText = p
            for (id in ids) {
                val views = if (Build.VERSION.SDK_INT >= 31) {
                    val today = LocalDate.now()
                    val i = info(d, today)
                    RemoteViews(mapOf(
                        SizeF(40f, 40f) to tiny(ctx, d, i, today),
                        SizeF(140f, 40f) to row(ctx, d, i, today),
                        SizeF(140f, 100f) to full(ctx, d, i, today, p, big = false),
                        SizeF(140f, 220f) to full(ctx, d, i, today, p, big = true),
                    ))
                } else {
                    val o = mgr.getAppWidgetOptions(id)
                    build(ctx, d, o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).toFloat(),
                        o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180).toFloat(), p)
                }
                runCatching { mgr.updateAppWidget(id, views) }
            }
        }
    }
}
