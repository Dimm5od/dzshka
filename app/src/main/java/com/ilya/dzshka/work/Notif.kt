package com.ilya.dzshka.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.work.ForegroundInfo
import com.ilya.dzshka.App
import com.ilya.dzshka.MainActivity
import com.ilya.dzshka.R
import com.ilya.dzshka.data.*
import java.time.LocalDate

object Notif {
    const val CH_RESULT = "result"
    const val CH_SENDING = "sending"
    const val KEY_REPLY = "reply_text"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun remindChannel(ctx: Context, sound: Boolean, vibrate: Boolean): String {
        val id = "remind_" + (if (sound) "s" else "") + (if (vibrate) "v" else "") + "_1"
        if (nm(ctx).getNotificationChannel(id) == null) {
            val ch = NotificationChannel(
                id,
                "Напоминания на переменах" + when {
                    sound && vibrate -> ""
                    sound -> " (без вибрации)"
                    vibrate -> " (без звука)"
                    else -> " (тихо)"
                },
                if (sound || vibrate) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT,
            )
            if (!sound) ch.setSound(null, null)
            else ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
            )
            ch.enableVibration(vibrate)
            if (vibrate) ch.vibrationPattern = longArrayOf(0, 250, 150, 250)
            nm(ctx).createNotificationChannel(ch)
        }
        return id
    }

    fun ensureChannels(ctx: Context) {
        val n = nm(ctx)
        if (n.getNotificationChannel(CH_RESULT) == null)
            n.createNotificationChannel(NotificationChannel(CH_RESULT, "Результат отправки", NotificationManager.IMPORTANCE_DEFAULT))
        if (n.getNotificationChannel(CH_SENDING) == null)
            n.createNotificationChannel(NotificationChannel(CH_SENDING, "Идёт отправка", NotificationManager.IMPORTANCE_LOW))
        if (n.getNotificationChannel(CH_LINK) == null)
            n.createNotificationChannel(NotificationChannel(CH_LINK, "Связь с помощниками (фон)", NotificationManager.IMPORTANCE_MIN))
        if (n.getNotificationChannel(CH_HELPERS) == null)
            n.createNotificationChannel(NotificationChannel(CH_HELPERS, "Помощники и их ДЗ", NotificationManager.IMPORTANCE_HIGH))
    }

    const val CH_LINK = "link"
    const val CH_HELPERS = "helpers"
    const val LINK_ID = 9100

    private fun openApp(ctx: Context, code: Int, route: String? = null): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (route != null) i.action = route
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun linkForeground(ctx: Context): android.app.Notification {
        ensureChannels(ctx)
        val helper = com.ilya.dzshka.link.Link.isHelper
        return NotificationCompat.Builder(ctx, CH_LINK)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (helper) "На связи с основным" else "На связи с помощниками")
            .setContentText("Можно скрыть: долгий тап → отключить эту категорию")
            .setContentIntent(openApp(ctx, 9101))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun helperAct(ctx: Context, action: String, code: Int, extra: Pair<String, String>): PendingIntent =
        pi(ctx, code, Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(extra.first, extra.second))

    fun newHelper(ctx: Context, h: Helper) {
        ensureChannels(ctx)
        val code = 9200 + (h.id.hashCode() and 0xFFF)
        val b = NotificationCompat.Builder(ctx, CH_HELPERS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Новый помощник: ${h.shownName}")
            .setContentText(if (h.status == HelperStatus.PENDING) "Хочет присылать ДЗ. Разрешить?" else "Подключился (${h.model})")
            .setContentIntent(openApp(ctx, code, MainActivity.ACTION_HELPERS))
            .setAutoCancel(true)
        if (h.status == HelperStatus.PENDING) {
            b.addAction(R.drawable.ic_notif, "Разрешить", helperAct(ctx, AlarmReceiver.ACTION_HELPER_OK, code * 3 + 1, "helper" to h.id))
            b.addAction(R.drawable.ic_notif, "Заблокировать", helperAct(ctx, AlarmReceiver.ACTION_HELPER_BLOCK, code * 3 + 2, "helper" to h.id))
        }
        if (canPost(ctx)) runCatching { NotificationManagerCompat.from(ctx).notify(code, b.build()) }
    }

    fun helperNotifId(entryId: String) = 9600 + (entryId.hashCode() and 0xFFFF)

    fun helperHw(ctx: Context, e: HwEntry, approval: Boolean) {
        ensureChannels(ctx)
        val code = helperNotifId(e.id)
        val text = "${e.subjectName}: " + (if (e.noHw) Store.d.templates.noHw else e.text.ifBlank { "📷 фото" }) +
            if (e.photos.isNotEmpty()) " (фото: ${e.photos.size})" else ""
        val b = NotificationCompat.Builder(ctx, CH_HELPERS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${e.author} прислал ДЗ" + if (approval) " — проверь" else "")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx, code))
            .setAutoCancel(true)
        if (approval) {
            b.addAction(R.drawable.ic_notif, "Одобрить", helperAct(ctx, AlarmReceiver.ACTION_HW_OK, code * 3 + 1, "entry" to e.id))
            b.addAction(R.drawable.ic_notif, "Отклонить", helperAct(ctx, AlarmReceiver.ACTION_HW_NO, code * 3 + 2, "entry" to e.id))
        }
        if (canPost(ctx)) runCatching { NotificationManagerCompat.from(ctx).notify(code, b.build()) }
    }

    fun helperPost(ctx: Context, pp: PendingPost) {
        ensureChannels(ctx)
        val code = helperNotifId(pp.id)
        val b = NotificationCompat.Builder(ctx, CH_HELPERS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("${pp.author} хочет написать в канал")
            .setContentText(pp.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(pp.text))
            .setContentIntent(openApp(ctx, code))
            .setAutoCancel(true)
            .addAction(R.drawable.ic_notif, "Отправить", helperAct(ctx, AlarmReceiver.ACTION_POST_OK, code * 3 + 1, "post" to pp.id))
            .addAction(R.drawable.ic_notif, "Отклонить", helperAct(ctx, AlarmReceiver.ACTION_POST_NO, code * 3 + 2, "post" to pp.id))
        if (canPost(ctx)) runCatching { NotificationManagerCompat.from(ctx).notify(code, b.build()) }
    }

    fun helperStatus(ctx: Context, st: HelperStatus) {
        ensureChannels(ctx)
        val (t, x) = when (st) {
            HelperStatus.ACTIVE -> "Основной тебя одобрил ✓" to "Теперь твои ДЗ уходят в канал"
            HelperStatus.BLOCKED -> "Основной тебя отключил" to "ДЗ больше не принимаются"
            HelperStatus.PENDING -> "Ждём одобрения основного" to "Можно уже присылать — основной решит"
        }
        val b = NotificationCompat.Builder(ctx, CH_HELPERS).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(t).setContentText(x).setContentIntent(openApp(ctx, 9301)).setAutoCancel(true)
        if (canPost(ctx)) runCatching { NotificationManagerCompat.from(ctx).notify(9301, b.build()) }
    }

    fun helperRejected(ctx: Context, o: HelperOut) {
        ensureChannels(ctx)
        val b = NotificationCompat.Builder(ctx, CH_HELPERS).setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("ДЗ не принято: ${o.subjectName}")
            .setContentText(o.note.ifBlank { "основной отклонил" })
            .setContentIntent(openApp(ctx, 9302)).setAutoCancel(true)
        if (canPost(ctx)) runCatching { NotificationManagerCompat.from(ctx).notify(9302 + (o.clientId.hashCode() and 0xFF), b.build()) }
    }

    fun canPost(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun pi(ctx: Context, code: Int, i: Intent, mutable: Boolean = false): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, code, i, flags)
    }

    fun lessonNotifId(date: String, index: Int) = (date.hashCode() * 31 + index) and 0x0FFFFFFF

    /** Кнопки, которые реально покажем (у помощника — только разрешённые). */
    fun visibleButtons(d: AppData, list: List<RemBtn>): List<RemBtn> {
        val helperApp = com.ilya.dzshka.link.Link.isHelper
        return list.filter { it.on }.distinctBy { it.kind }.filter { b ->
            !helperApp || when (b.kind) {
                NotifButton.NO_HW -> com.ilya.dzshka.link.HelperLink.can(d, HelperPerm.NO_HW)
                NotifButton.SKIP -> com.ilya.dzshka.link.HelperLink.can(d, HelperPerm.SKIP)
                else -> true
            }
        }
    }

    /** «Расширенно»: строки про прошедший, следующий и прошлый уроки. */
    fun extraLines(d: AppData, date: LocalDate, index: Int, subjectId: String, nowMin: Int? = null): List<String> {
        val r = d.reminder
        if (!r.extended) return emptyList()
        val x = r.extra
        val lessons = Sched.lessonsOn(d, date)
        val cur = lessons.firstOrNull { it.index == index } ?: lessons.firstOrNull { it.subjectId == subjectId }
        val i = if (cur == null) -1 else lessons.indexOf(cur)
        val next = if (i >= 0) lessons.getOrNull(i + 1) else null
        val prev = if (i > 0) lessons.getOrNull(i - 1) else null
        fun name(l: Lesson) = "${l.number}. " + (d.subject(l.subjectId)?.name ?: "?")
        val out = mutableListOf<String>()
        if (x.passed && cur != null) out += "✅ Прошёл: ${name(cur)}"
        if (x.next) {
            if (next == null) out += "🏁 Это был последний урок"
            else {
                val left = if (x.timeLeft && next.start != null && nowMin != null && date == LocalDate.now()) next.start - nowMin else null
                out += "⏭ Дальше: ${name(next)}" + (next.start?.let { " в ${Time.fmt(it)}" } ?: "") +
                    (if (left != null && left >= 0) " (через $left мин)" else "")
            }
        }
        if (x.breakLen && next?.start != null && cur?.end != null) out += "☕ Перемена: ${next.start - cur.end} мин"
        if (x.prev && prev != null) {
            val hw = d.hwLog["${date}|${prev.subjectId}"]
            out += "↩ Прошлый урок: ${name(prev)}" + if (x.prevHw) " — ДЗ: ${hw ?: "не записано"}" else ""
        }
        return out
    }

    fun nowMin(): Int = java.time.LocalTime.now().let { it.hour * 60 + it.minute }

    fun reminderCtx(d: AppData, date: LocalDate, number: Int, subjectId: String): TplCtx {
        val subj = d.subject(subjectId)
        return TplCtx(subject = subj, subjectName = subj?.name ?: "", due = Sched.dueFor(d, d.dueMode, subjectId, date), lessonDate = date, lessonNum = number.takeIf { it > 0 })
    }

    /** Напоминание на перемене: сообщение или экранчик — как выбрано в настройках. test = из «тестовой недели». */
    fun remind(ctx: Context, date: LocalDate, index: Int, number: Int, subjectId: String, test: Boolean = false, testIndex: Int = -1) {
        val d = Store.d
        val cfg = d.reminder
        if (d.subject(subjectId) == null) return
        val nid = if (test) TestRun.NOTIF_ID else lessonNotifId(date.toString(), index)
        val screen = cfg.style == ReminderStyle.POPUP && !com.ilya.dzshka.link.Link.isHelper
        if (screen) {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
            val unlocked = pm.isInteractive && !km.isKeyguardLocked
            // экран включён и разблокирован — показываем экранчик сразу, без сообщения
            if (unlocked && (App.visible || android.provider.Settings.canDrawOverlays(ctx))) {
                val ok = runCatching { ctx.startActivity(com.ilya.dzshka.ui.PopupActivity.intent(ctx, date, index, number, subjectId, nid, test, testIndex)) }.isSuccess
                if (ok) return
            }
        }
        val c = reminderCtx(d, date, number, subjectId)
        val title = (if (test) "🧪 " else "") + Builder.r(d, d.templates.notifTitle, c)
        val extra = extraLines(d, date, index, subjectId, nowMin())
        val text = Builder.r(d, d.templates.notifText, c) + if (extra.isNotEmpty()) "\n" + extra.joinToString("\n") else ""

        val open = if (test) Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_TEST)
            else Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_ADD)
                .putExtra("subject", subjectId).putExtra("date", date.toString()).putExtra("num", number)
        open.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val openPi = PendingIntent.getActivity(ctx, nid, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun act(a: String) = Intent(ctx, AlarmReceiver::class.java).apply {
            action = a
            putExtra("subject", subjectId); putExtra("date", date.toString()); putExtra("num", number)
            putExtra("index", index); putExtra("nid", nid)
        }

        val b = NotificationCompat.Builder(ctx, remindChannel(ctx, cfg.sound, cfg.vibrate))
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (cfg.autoCancelMin > 0 && !test) b.setTimeoutAfter(cfg.autoCancelMin * 60_000L)
        // кнопки — как настроено в Студии (Android показывает не больше трёх)
        val subjName = d.subject(subjectId)?.name ?: ""
        visibleButtons(d, cfg.notifButtons).take(3).forEach { btn ->
            when (btn.kind) {
                NotifButton.REPLY -> {
                    val ri = RemoteInput.Builder(KEY_REPLY).setLabel("Что задали по предмету «$subjName»?").build()
                    b.addAction(NotificationCompat.Action.Builder(R.drawable.ic_notif, btn.text,
                        pi(ctx, nid * 5 + 1, act(if (test) AlarmReceiver.ACTION_TEST_REPLY else AlarmReceiver.ACTION_REPLY), mutable = true))
                        .addRemoteInput(ri).setAllowGeneratedReplies(false).build())
                }
                NotifButton.SKIP -> b.addAction(R.drawable.ic_notif, btn.text, pi(ctx, nid * 5 + 4, act(if (test) AlarmReceiver.ACTION_TEST_SKIP else AlarmReceiver.ACTION_SKIP)))
                NotifButton.SNOOZE -> b.addAction(R.drawable.ic_notif, btn.text, pi(ctx, nid * 5 + 3, act(if (test) AlarmReceiver.ACTION_TEST_SKIP else AlarmReceiver.ACTION_SNOOZE)))
                NotifButton.NO_HW -> b.addAction(R.drawable.ic_notif, btn.text, pi(ctx, nid * 5 + 2, act(if (test) AlarmReceiver.ACTION_TEST_NOHW else AlarmReceiver.ACTION_NO_HW)))
            }
        }
        // экранчик на заблокированном / выключенном экране
        if (screen) {
            val fs = PendingIntent.getActivity(ctx, nid * 5 + 6,
                com.ilya.dzshka.ui.PopupActivity.intent(ctx, date, index, number, subjectId, nid, test, testIndex),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            b.setFullScreenIntent(fs, true)
        }
        if (canPost(ctx)) runCatching { NotificationManagerCompat.from(ctx).notify(nid, b.build()) }
    }

    fun done(ctx: Context, nid: Int, text: String) {
        ensureChannels(ctx)
        val b = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Записано ✓")
            .setContentText(text)
            .setTimeoutAfter(5000)
            .setAutoCancel(true)
        if (canPost(ctx)) runCatching { NotificationManagerCompat.from(ctx).notify(nid, b.build()) }
    }

    fun updated(ctx: Context, name: String) {
        ensureChannels(ctx)
        val b = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Обновлено до $name ✓")
            .setContentText("Нажми, чтобы открыть")
            .setContentIntent(openApp(ctx, 9010))
            .setAutoCancel(true)
        if (canPost(ctx)) runCatching { NotificationManagerCompat.from(ctx).notify(9010, b.build()) }
    }

    fun result(ctx: Context, ok: Boolean, text: String) {
        val d = Store.d
        if (ok && (!d.send.notifyResult || App.visible)) return
        ensureChannels(ctx)
        val open = PendingIntent.getActivity(
            ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(ctx, CH_RESULT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(if (ok) "ДЗ отправлено в Viber ✓" else "ДЗ не отправилось ✗")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (canPost(ctx)) runCatching { NotificationManagerCompat.from(ctx).notify(if (ok) 9001 else 9002, b.build()) }
    }

    fun sendingForeground(ctx: Context): ForegroundInfo {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CH_SENDING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Отправляю ДЗ в Viber…")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(9003, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(9003, n)
    }

    fun cancel(ctx: Context, nid: Int) = NotificationManagerCompat.from(ctx).cancel(nid)
}
