package com.ilya.dzshka.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.ilya.dzshka.BuildConfig
import com.ilya.dzshka.MainActivity
import com.ilya.dzshka.R
import com.ilya.dzshka.data.AppData
import com.ilya.dzshka.ui.NowState
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * «Сейчас» на заблокированном экране: тихое закреплённое уведомление во время учебного дня —
 * какой урок или перемена, обратный отсчёт до звонка и полоска. После уроков убирается само.
 */
object NowNotif {
    const val CH = "now_lesson"
    const val ID = 4401
    /** за сколько минут до первого урока появляться */
    const val BEFORE_MIN = 60

    fun wanted(d: AppData, i: NowState.Info): Boolean =
        !BuildConfig.HELPER && d.widget.lockNotif && i.live &&
            (!i.beforeStart || (i.untilMin != null && i.untilMin - minuteOfDay() <= BEFORE_MIN))

    private fun minuteOfDay() = LocalDateTime.now().let { it.hour * 60 + it.minute }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun channel(ctx: Context) {
        if (nm(ctx).getNotificationChannel(CH) != null) return
        nm(ctx).createNotificationChannel(NotificationChannel(CH, "Сейчас урок (на экране блокировки)", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }

    fun update(ctx: Context, d: AppData, i: NowState.Info, now: LocalDateTime = LocalDateTime.now()) {
        runCatching {
            if (!wanted(d, i)) { cancel(ctx); return }
            channel(ctx)
            val open = PendingIntent.getActivity(ctx, 5, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(i.big)
                .setContentText(i.line)
                .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(i.small, i.next).filter { it.isNotBlank() }.joinToString("\n")))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(open)
            i.prog?.let { b.setProgress(1000, (it.coerceIn(0f, 1f) * 1000).toInt(), false) }
            // обратный отсчёт до звонка идёт сам, даже когда экран выключен
            i.untilMin?.let { u ->
                val at = now.toLocalDate().atStartOfDay().plusMinutes(u.toLong()).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                b.setWhen(at).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            }
            nm(ctx).notify(ID, b.build())
        }
    }

    fun cancel(ctx: Context) { runCatching { nm(ctx).cancel(ID) } }
}
