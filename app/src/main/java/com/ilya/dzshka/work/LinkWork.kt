package com.ilya.dzshka.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.ilya.dzshka.data.*
import com.ilya.dzshka.link.*
import com.ilya.dzshka.net.Uploader
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Помощник: загрузить фото и отправить ДЗ основному. */
class HelperSendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    companion object {
        fun enqueue(ctx: Context, entryId: String) {
            val req = OneTimeWorkRequestBuilder<HelperSendWorker>()
                .setInputData(workDataOf("id" to entryId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("hsend_$entryId", ExistingWorkPolicy.KEEP, req)
        }
    }

    override fun doWork(): Result {
        Store.init(applicationContext)
        val id = inputData.getString("id") ?: return Result.success()
        val e0 = Store.d.queue.firstOrNull { it.id == id } ?: return Result.success()
        return try {
            // 1. фото — на хостинг (основной получит только ссылки)
            for (f in e0.photos) {
                if (Store.d.queue.first { it.id == id }.uploaded.containsKey(f)) continue
                val file = File(Store.photosDir(), f)
                if (!file.exists()) continue
                val url = Uploader.upload(file, Store.d.photo)
                Store.updateEntry(id) { it.copy(uploaded = it.uploaded + (f to url)) }
            }
            val e = Store.d.queue.first { it.id == id }
            val hw = HwPayload(
                clientId = e.clientId, subjectId = e.subjectId, subjectName = e.subjectName, text = e.text, noHw = e.noHw,
                due = e.due, lessonDate = e.lessonDate, lessonNum = e.lessonNum,
                photoUrls = e.photos.mapNotNull { e.uploaded[it] },
            )
            // 2. само ДЗ
            Link.send(Packet(Packet.HW, Store.d.link.deviceId, hw = hw, name = Store.d.link.myName))
            Store.update { d ->
                d.copy(outbox = d.outbox.map { if (it.clientId == e.clientId) it.copy(note = "отправлено, ждём ответ основного", error = "") else it })
            }
            val keys = listOf("${e.lessonDate}|${e.subjectId}")
            Store.update { s -> s.copy(sentKeys = (keys + s.sentKeys).distinct().take(300)) }
            Store.removeEntry(id, true)
            Store.saveNow()
            // 3. ответ основного обычно приходит за пару секунд
            repeat(4) {
                Thread.sleep(2500)
                runCatching { Link.pollNow(applicationContext) }
                if (Store.d.outbox.firstOrNull { it.clientId == e.clientId }?.state != null) return Result.success()
            }
            Result.success()
        } catch (ex: Exception) {
            val msg = ex.message ?: ex.javaClass.simpleName
            val retry = ex is IOException && runAttemptCount < 5
            Store.updateEntry(id) { it.copy(status = if (retry) EntryStatus.SENDING else EntryStatus.FAILED, error = if (retry) "Нет связи, повторю" else msg) }
            Store.update { d -> d.copy(outbox = d.outbox.map { if (it.clientId == e0.clientId) it.copy(error = if (retry) "нет связи, повторю" else msg) else it }) }
            Store.saveNow()
            if (retry) Result.retry() else Result.failure()
        }
    }
}

/** Раз в 15 минут проверить новые сообщения (запасной путь, если постоянная связь выключена или упала). */
class LinkPollWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<LinkPollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("linkpoll", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun now(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<LinkPollWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("linkpoll_now", ExistingWorkPolicy.REPLACE, req)
        }
    }

    override fun doWork(): Result {
        Store.init(applicationContext)
        if (!Store.d.link.active) return Result.success()
        return runCatching {
            if (Link.isHelper) {
                val l = Store.d.link
                // здороваемся не чаще раза в 3 часа или если расписания ещё нет
                if (l.cfgVer == 0L || System.currentTimeMillis() - l.lastContact > 3 * 3600_000L) HelperLink.hello()
            }
            Link.pollNow(applicationContext)
            Store.saveNow()
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

/** Постоянная связь: держит соединение с сервером и сразу получает сообщения. */
class LinkService : Service() {
    companion object {
        @Volatile var running = false
            private set
        @Volatile var state: String = ""
            private set

        // у помощника постоянной связи нет (меньше разрешений) — только проверки по звонкам и раз в 15 минут
        fun shouldRun(d: AppData) = !Link.isHelper && d.link.active && d.link.listenAlways &&
            (d.helpers.isNotEmpty() || System.currentTimeMillis() - d.link.connectedAt < 7 * 24 * 3600_000L)

        fun sync(ctx: Context) {
            Store.init(ctx)
            val want = shouldRun(Store.d)
            if (want && !running) runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java)) }
            if (!want && running) ctx.stopService(Intent(ctx, LinkService::class.java))
            if (Store.d.link.active) LinkPollWorker.schedule(ctx)
        }
    }

    @Volatile private var alive = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        val n = Notif.linkForeground(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0
        runCatching { ServiceCompat.startForeground(this, Notif.LINK_ID, n, type) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        running = true
        if (worker?.isAlive != true) {
            alive = true
            worker = thread(name = "dz-link") { loop() }
        }
        return START_STICKY
    }

    private fun loop() {
        var backoff = 2_000L
        while (alive) {
            val l = Store.d.link
            if (!l.active) break
            try {
                if (Link.isHelper) runCatching { HelperLink.hello() }
                state = "на связи"
                Ntfy.stream(l.server, Link.inTopic(l), Link.lastId(l).ifBlank { "12h" }, { alive }) { m ->
                    Link.process(this, listOf(m))
                    backoff = 2_000L
                }
            } catch (e: Exception) {
                state = "нет связи: ${e.message ?: e.javaClass.simpleName}"
            }
            if (!alive) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(120_000L)
        }
    }

    override fun onDestroy() {
        alive = false
        running = false
        worker?.interrupt()
        super.onDestroy()
    }
}
