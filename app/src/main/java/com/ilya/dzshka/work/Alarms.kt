package com.ilya.dzshka.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.RemoteInput
import com.ilya.dzshka.data.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

object Alarms {
    private const val RC_REMIND = 100

    @Volatile var lastPlanned: String = ""
        private set

    private fun am(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    fun canExact(ctx: Context) = Build.VERSION.SDK_INT < 31 || am(ctx).canScheduleExactAlarms()

    private fun setAt(ctx: Context, time: LocalDateTime, pi: PendingIntent) {
        val ms = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val a = am(ctx)
        if (canExact(ctx)) a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        else a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }

    private fun bpi(ctx: Context, rc: Int, i: Intent) =
        PendingIntent.getBroadcast(ctx, rc, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun fmt(d: AppData, t: LocalDateTime) =
        "${d.weekdaysShort.getOrElse(t.dayOfWeek.value - 1) { "" }} %02d.%02d %s".format(t.dayOfMonth, t.monthValue, Time.fmt(t.hour * 60 + t.minute))

    fun reschedule(ctx: Context) {
        Store.init(ctx)
        val d = Store.d
        val now = LocalDateTime.now()
        val plan = StringBuilder()

        val rem = Sched.reminders(d, now).firstOrNull()
        val remIntent = Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_REMIND)
        if (rem != null) {
            remIntent.putExtra("subject", rem.lesson.subjectId)
                .putExtra("date", rem.lesson.date.toString())
                .putExtra("index", rem.lesson.index)
                .putExtra("num", rem.lesson.number)
            setAt(ctx, rem.time, bpi(ctx, RC_REMIND, remIntent))
            plan.append("напоминание ${fmt(d, rem.time)} — ${d.subject(rem.lesson.subjectId)?.name}")
        } else am(ctx).cancel(bpi(ctx, RC_REMIND, remIntent))

        // моменты отправки из студии — у каждого свой будильник (слоты 300..331)
        val timed = d.moments.mapNotNull { m -> Studio.nextFire(d, m, now)?.let { m to it } }.take(32)
        for (slot in 0 until 32) {
            val i = Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_MOMENT)
            val item = timed.getOrNull(slot)
            if (item != null) {
                i.putExtra("moment", item.first.id)
                setAt(ctx, item.second, bpi(ctx, 300 + slot, i))
            } else am(ctx).cancel(bpi(ctx, 300 + slot, i))
        }
        timed.minByOrNull { it.second }?.let { (m, t) ->
            if (plan.isNotEmpty()) plan.append("\n")
            plan.append("«${m.name.ifBlank { Studio.whenText(m) }}» ${fmt(d, t)}")
        }
        lastPlanned = plan.toString()
    }

    fun snooze(ctx: Context, src: Intent, minutes: Int) {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_REMIND_SNOOZED).putExtras(src)
        val nid = src.getIntExtra("nid", 0)
        setAt(ctx, LocalDateTime.now().plusMinutes(minutes.toLong()), bpi(ctx, 200 + (nid and 0xFFFF), i))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_REMIND = "com.ilya.dzshka.REMIND"
        const val ACTION_REMIND_SNOOZED = "com.ilya.dzshka.REMIND_SNOOZED"
        const val ACTION_MOMENT = "com.ilya.dzshka.MOMENT"
        const val ACTION_NO_HW = "com.ilya.dzshka.NO_HW"
        const val ACTION_REPLY = "com.ilya.dzshka.REPLY"
        const val ACTION_SNOOZE = "com.ilya.dzshka.SNOOZE"
        const val ACTION_SKIP = "com.ilya.dzshka.SKIP"
        const val ACTION_HELPER_OK = "com.ilya.dzshka.HELPER_OK"
        const val ACTION_HELPER_BLOCK = "com.ilya.dzshka.HELPER_BLOCK"
        const val ACTION_HW_OK = "com.ilya.dzshka.HW_OK"
        const val ACTION_HW_NO = "com.ilya.dzshka.HW_NO"
        const val ACTION_POST_OK = "com.ilya.dzshka.POST_OK"
        const val ACTION_POST_NO = "com.ilya.dzshka.POST_NO"
        const val ACTION_TEST_REPLY = "com.ilya.dzshka.TEST_REPLY"
        const val ACTION_TEST_NOHW = "com.ilya.dzshka.TEST_NOHW"
        const val ACTION_TEST_AUTO = "com.ilya.dzshka.TEST_AUTO"
        const val ACTION_TEST_SKIP = "com.ilya.dzshka.TEST_SKIP"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Store.init(ctx)
        val subject = intent.getStringExtra("subject") ?: ""
        val date = intent.getStringExtra("date")?.let { Sched.parseDate(it) } ?: LocalDate.now()
        val num = intent.getIntExtra("num", 0)
        val index = intent.getIntExtra("index", 0)
        val nid = intent.getIntExtra("nid", Notif.lessonNotifId(date.toString(), index))
        when (intent.action) {
            ACTION_REMIND -> {
                val already = Store.d.queue.any { it.subjectId == subject && it.lessonDate == date.toString() } ||
                    Store.d.isSent(subject, date.toString()) || Store.d.isSkipped(subject, date.toString()) ||
                    Store.d.isFromChat(subject, date.toString())
                if (!already) Notif.remind(ctx, date, index, num, subject)
                Alarms.reschedule(ctx)
                if (com.ilya.dzshka.link.Link.isHelper) LinkPollWorker.now(ctx)
            }
            ACTION_REMIND_SNOOZED -> if (!Store.d.isFromChat(subject, date.toString())) Notif.remind(ctx, date, index, num, subject)
            ACTION_MOMENT -> {
                intent.getStringExtra("moment")?.let { Sender.fireMoment(ctx, it) }
                Alarms.reschedule(ctx)
            }
            ACTION_NO_HW -> {
                val e = Entries.create(Store.d, subject, "", date, num, "", noHw = true)
                Entries.addAndMaybeSend(ctx, e)
                Store.saveNow()
                Notif.done(ctx, nid, "${e.subjectName}: ${Store.d.templates.noHw}")
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notif.KEY_REPLY)?.toString()?.trim().orEmpty()
                if (text.isEmpty()) { Notif.cancel(ctx, nid); return }
                val e = Entries.create(Store.d, subject, "", date, num, text)
                Entries.addAndMaybeSend(ctx, e)
                Store.saveNow()
                Notif.done(ctx, nid, "${e.subjectName}: $text")
            }
            ACTION_TEST_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notif.KEY_REPLY)?.toString()?.trim().orEmpty()
                TestRun.answer(if (text.isEmpty()) TestRun.Answer.Auto else TestRun.Answer.Text(text))
                Notif.cancel(ctx, TestRun.NOTIF_ID)
            }
            ACTION_TEST_NOHW -> { TestRun.answer(TestRun.Answer.NoHw); Notif.cancel(ctx, TestRun.NOTIF_ID) }
            ACTION_TEST_AUTO -> { TestRun.answer(TestRun.Answer.Auto); Notif.cancel(ctx, TestRun.NOTIF_ID) }
            ACTION_TEST_SKIP -> { TestRun.answer(TestRun.Answer.Skip); Notif.cancel(ctx, TestRun.NOTIF_ID) }
            ACTION_HELPER_OK, ACTION_HELPER_BLOCK -> {
                val id = intent.getStringExtra("helper") ?: return
                val st = if (intent.action == ACTION_HELPER_OK) HelperStatus.ACTIVE else HelperStatus.BLOCKED
                val pr = goAsync()
                Thread {
                    runCatching { com.ilya.dzshka.link.MainLink.updateHelper(id) { it.copy(status = st) } }
                    Store.saveNow()
                    Notif.cancel(ctx, 9200 + (id.hashCode() and 0xFFF))
                    pr.finish()
                }.start()
            }
            ACTION_HW_OK, ACTION_HW_NO -> {
                val id = intent.getStringExtra("entry") ?: return
                val ok = intent.action == ACTION_HW_OK
                val pr = goAsync()
                Thread {
                    runCatching { if (ok) com.ilya.dzshka.link.MainLink.approve(ctx, id) else com.ilya.dzshka.link.MainLink.reject(ctx, id) }
                    Notif.cancel(ctx, Notif.helperNotifId(id))
                    pr.finish()
                }.start()
            }
            ACTION_POST_OK, ACTION_POST_NO -> {
                val id = intent.getStringExtra("post") ?: return
                val ok = intent.action == ACTION_POST_OK
                val pr = goAsync()
                Thread {
                    runCatching {
                        if (ok) Store.d.pendingPosts.firstOrNull { it.id == id }?.let { com.ilya.dzshka.link.MainLink.postNow(it) }
                        else com.ilya.dzshka.link.MainLink.rejectPost(id)
                    }
                    Notif.cancel(ctx, Notif.helperNotifId(id))
                    pr.finish()
                }.start()
            }
            ACTION_SKIP -> {
                if (com.ilya.dzshka.link.Link.isHelper) com.ilya.dzshka.link.HelperLink.sendSkip(ctx, subject, date)
                else Entries.skip(subject, date)
                Notif.cancel(ctx, nid)
            }
            ACTION_SNOOZE -> {
                Notif.cancel(ctx, nid)
                Alarms.snooze(ctx, intent, Store.d.reminder.snoozeMin.coerceAtLeast(1))
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Alarms.reschedule(ctx)
        runCatching { LinkService.sync(ctx) }
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) runCatching {
            java.io.File(ctx.cacheDir, "update").deleteRecursively()
            Notif.updated(ctx, com.ilya.dzshka.BuildConfig.VERSION_NAME)
        }
    }
}
