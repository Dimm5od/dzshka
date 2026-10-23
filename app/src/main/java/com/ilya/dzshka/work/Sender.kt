package com.ilya.dzshka.work

import android.content.Context
import androidx.work.*
import com.ilya.dzshka.data.*
import com.ilya.dzshka.net.Uploader
import com.ilya.dzshka.net.Viber
import com.ilya.dzshka.net.ViberException
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit

object Entries {
    fun create(
        d: AppData,
        subjectId: String,
        subjectName: String,
        lessonDate: LocalDate,
        lessonNum: Int,
        text: String,
        photos: List<String> = emptyList(),
        noHw: Boolean = false,
        due: LocalDate? = null,
    ): HwEntry = HwEntry(
        subjectId = subjectId,
        subjectName = d.subject(subjectId)?.name ?: subjectName,
        text = text,
        photos = photos,
        noHw = noHw,
        lessonDate = lessonDate.toString(),
        lessonNum = lessonNum,
        due = (due ?: Sched.dueFor(d, d.dueMode, subjectId, lessonDate)).toString(),
    )

    /** «Нету ДЗ» (не задали): урок пропускается, в канал ничего не уходит, напоминания по нему больше нет. */
    fun skip(subjectId: String, date: LocalDate) {
        val key = "$date|$subjectId"
        Store.d.subject(subjectId)?.let { s ->
            Archive.put(HwRecord(id = key, subjectId = s.id, subjectName = s.name, none = true, lessonDate = date.toString(),
                due = Sched.dueFor(Store.d, Store.d.dueMode, s.id, date).toString()))
        }
        Store.update { it.copy(mascot = it.mascot.copy(event = "noHwGiven", eventAt = System.currentTimeMillis())) }
        Store.update { d -> d.copy(hwLog = (d.hwLog - key + (key to "не задали")).entries.toList().takeLast(200).associate { it.toPair() }) }
        val name = Store.d.subject(subjectId)?.name ?: ""
        Store.update { it.copy(skipped = (listOf(key) + it.skipped.filterNot { k -> k == key }).take(300)) }
        Store.saveNow()
        if (name.isNotBlank()) Store.addHistory(SentRecord(text = "$name — не задали", ok = true))
    }

    fun unskip(subjectId: String, date: LocalDate) {
        val key = "$date|$subjectId"
        Store.update { it.copy(skipped = it.skipped.filterNot { k -> k == key }) }
        Archive.remove(date.toString(), subjectId)
    }

    /** Добавить запись; если в студии есть момент «сразу после ввода» — отправить. */
    fun logHw(e: HwEntry, text: String) {
        val key = "${e.lessonDate}|${e.subjectId}"
        Store.update { d -> d.copy(hwLog = (d.hwLog - key + (key to text)).entries.toList().takeLast(200).associate { it.toPair() }) }
    }

    fun addAndMaybeSend(ctx: Context, e: HwEntry) {
        logHw(e, if (e.noHw) Store.d.templates.noHw else e.text.ifBlank { if (e.photos.isNotEmpty()) "📷 фото" else "" })
        if (com.ilya.dzshka.link.Link.isHelper) { com.ilya.dzshka.link.HelperLink.submit(ctx, e); return }
        Store.update { it.copy(queue = it.queue + e) }
        Studio.onAddMoment(Store.d)?.let { Sender.enqueue(ctx, it.id, listOf(e.id)) }
    }
}

/** Журнал сообщений, отправленных в канал из приложения. */
object ChannelLog {
    fun record(token: String, text: String, photoUrl: String, author: String) {
        Store.update { d -> d.copy(channelPosts = (listOf(ChannelPost(token = token, text = text, photoUrl = photoUrl, author = author)) + d.channelPosts).take(300)) }
    }
}

object Sender {
    private const val KEY_IDS = "ids"
    private const val KEY_MOMENT = "moment"
    private const val KEY_TEST = "test"

    /** «🧪 в тест-канал»: отправить копии записей во второй канал. Очередь и отметки «отправлено» не меняются. */
    fun sendTest(ctx: Context, entries: List<HwEntry>, momentId: String) {
        val copies = entries.map { it.copy(id = newId(), status = EntryStatus.SENDING, error = "", photosPosted = emptyList(), textSent = false) }
        Store.update { it.copy(testQueue = it.testQueue + copies) }
        Store.saveNow()
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf(KEY_IDS to copies.map { it.id }.toTypedArray(), KEY_MOMENT to momentId, KEY_TEST to true))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("send-test", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    /** Убрать отправленные/сломанные тестовые копии и фото, которые больше никому не нужны. */
    internal fun dropTest(ids: List<String>) {
        val gone = Store.d.testQueue.filter { it.id in ids }
        Store.update { it.copy(testQueue = it.testQueue.filterNot { e -> e.id in ids }) }
        val used = (Store.d.queue + Store.d.testQueue).flatMap { it.photos }.toSet()
        gone.flatMap { it.photos }.filter { it !in used }.forEach { File(Store.photosDir(), it).delete() }
        Store.saveNow()
    }

    fun enqueue(ctx: Context, momentId: String, ids: List<String>) {
        Store.update { d ->
            d.copy(queue = d.queue.map { if (it.id in ids) it.copy(status = EntryStatus.SENDING, error = "") else it })
        }
        val req = OneTimeWorkRequestBuilder<SendWorker>()
            .setInputData(workDataOf(KEY_IDS to ids.toTypedArray(), KEY_MOMENT to momentId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("send", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    private fun waiting(d: AppData) = d.queue.filter { it.status != EntryStatus.SENDING && it.status != EntryStatus.APPROVAL }.map { it.id }

    /** Кнопка «Отправить очередь». */
    fun sendQueue(ctx: Context) {
        val d = Store.d
        val ids = waiting(d)
        if (ids.isEmpty()) return
        val m = Studio.queueMoment(d)
        if (m.trigger == Trigger.ON_ADD) ids.forEach { enqueue(ctx, m.id, listOf(it)) } else enqueue(ctx, m.id, ids)
    }

    /** Отправить конкретные записи прямо сейчас. */
    fun sendNow(ctx: Context, ids: List<String>) {
        val m = Studio.nowMoment(Store.d)
        if (m.trigger == Trigger.ON_ADD) ids.forEach { enqueue(ctx, m.id, listOf(it)) } else enqueue(ctx, m.id, ids)
    }

    /** Сработал момент по времени. */
    fun fireMoment(ctx: Context, momentId: String) {
        val d = Store.d
        val m = d.moments.firstOrNull { it.id == momentId && it.enabled } ?: return
        val ids = if (m.hasHwSteps) waiting(d) else emptyList()
        if (ids.isEmpty() && m.steps.all { it.kind.usesEntries || it.onlyIfHw }) return
        enqueue(ctx, m.id, ids)
    }

    class SendWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
        override fun doWork(): Result {
            Store.init(applicationContext)
            val ids = inputData.getStringArray(KEY_IDS)?.toList() ?: emptyList()
            val momentId = inputData.getString(KEY_MOMENT) ?: ""
            val test = inputData.getBoolean(KEY_TEST, false)
            if (test) return try {
                val sentText = run(momentId, ids, true)
                dropTest(ids)
                if (sentText != null) Notif.result(applicationContext, true, sentText)
                Result.success()
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                if (e is IOException && e !is ViberException && runAttemptCount < 3) Result.retry()
                else {
                    dropTest(ids)
                    Store.addHistory(SentRecord(text = "🧪 Тест-канал: не отправлено", ok = false, error = msg))
                    Notif.result(applicationContext, false, "🧪 Тест-канал: $msg")
                    Result.failure()
                }
            }
            return try {
                val sentText = run(momentId, ids, false)
                if (sentText != null) Notif.result(applicationContext, true, sentText)
                Result.success()
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                // нет связи — пробуем снова сами (примерно сутки), ДЗ не теряется
                val retry = e is IOException && e !is ViberException && runAttemptCount < 12
                Store.update { d ->
                    d.copy(queue = d.queue.map {
                        if (it.id in ids) it.copy(
                            status = if (retry) EntryStatus.SENDING else EntryStatus.FAILED,
                            error = if (retry) "Нет связи, отправлю сам, когда появится. $msg" else msg,
                        ) else it
                    })
                }
                Store.saveNow()
                if (retry) Result.retry() else {
                    Store.addHistory(SentRecord(text = "Не отправлено" + if (ids.isNotEmpty()) " (${ids.size} шт.)" else "", ok = false, error = msg))
                    Notif.result(applicationContext, false, msg)
                    Result.failure()
                }
            }
        }

        private var test = false
        private fun cur(id: String): HwEntry? = (if (test) Store.d.testQueue else Store.d.queue).firstOrNull { it.id == id }

        /** @return текст для уведомления или null, если отправлять было нечего */
        private fun run(momentId: String, ids: List<String>, test: Boolean): String? {
            this.test = test
            val d = Store.d
            val v = if (test) d.testViber else d.viber
            val where = if (test) "Настройки → 🧪 Тест-канал" else "Настройки → Viber"
            if (v.token.isBlank()) throw IllegalStateException("Не указан токен ${if (test) "тест-канала" else "Viber-канала"} ($where)")
            if (v.fromId.isBlank()) throw IllegalStateException("Не выбран отправитель. $where → «Проверить токен»")
            val moment = Studio.findMoment(d, momentId) ?: Studio.queueMoment(d)
            val entries = (if (test) d.testQueue else d.queue).filter { it.id in ids }
            val today = LocalDate.now()
            val posts = Studio.plan(d, moment.steps, entries, today, if (test) emptyMap() else d.sentOnce)
            if (posts.isEmpty() && entries.isEmpty()) return null

            val texts = mutableListOf<String>()
            var photoCount = 0
            for (p in posts) {
                val owners = p.owners.mapNotNull { cur(it) }
                if (owners.isNotEmpty() && owners.all { p.key in it.photosPosted }) continue
                when (p) {
                    is Post.Text -> {
                        if (!test && p.onceKey != null && Store.d.sentOnce[p.onceKey] == today.toString() && p.key.endsWith(":0")) continue
                        val tok = Viber.postText(v.token, v.fromId, p.text, v.minApi)
                        if (!test) ChannelLog.record(tok, p.text, "", p.owners.mapNotNull { cur(it)?.author }.distinct().joinToString(", "))
                        texts += p.text
                        if (!test) p.onceKey?.let { k -> Store.update { s -> s.copy(sentOnce = s.sentOnce + (k to today.toString())) } }
                    }
                    is Post.Photo -> {
                        val e = cur(p.entryId) ?: continue
                        val url = e.uploaded[p.file] ?: run {
                            val f = File(Store.photosDir(), p.file)
                            if (!f.exists()) {
                                Store.addHistory(SentRecord(text = "${e.subjectName}: фото не найдено на телефоне, пропущено", ok = false, error = p.file))
                                null
                            } else {
                            val u = try { Uploader.upload(f, Store.d.photo) } catch (x: IOException) {
                                throw IOException("Фото не загрузилось на ${Store.d.photo.hosting.label.substringBefore(" ")} (${f.length() / 1024} КБ): ${x.message}", x)
                            }
                            if (!test) Store.updateEntry(e.id) { it.copy(uploaded = it.uploaded + (p.file to u)) }
                            u
                            }
                        }
                        if (url != null) {
                        val tok = try { Viber.postPicture(v.token, v.fromId, url, p.caption, v.minApi) } catch (x: ViberException) {
                            throw ViberException(x.code, "Viber не принял фото $url — ${x.message}")
                        }
                        if (!test) ChannelLog.record(tok, p.caption, url, e.author)
                        photoCount++
                        }
                    }
                }
                if (!test && p.owners.isNotEmpty()) Store.update { s ->
                    s.copy(queue = s.queue.map { if (it.id in p.owners) it.copy(photosPosted = it.photosPosted + p.key) else it })
                }
                Store.saveNow()
            }

            val summary = texts.joinToString("\n\n").ifBlank { if (photoCount > 0) "Фото: $photoCount" else "" }
            if (test) {
                Store.addHistory(SentRecord(text = "🧪 Тест-канал: " + summary.ifBlank { "нечего отправлять" }, photos = photoCount, ok = true))
                return if (summary.isBlank()) null else "🧪 $summary"
            }
            if (summary.isNotBlank()) Store.addHistory(SentRecord(text = summary, photos = photoCount, ok = true))
            Store.update { s -> s.copy(hwInfo = (s.hwInfo + entries.map { e ->
                "${e.lessonDate}|${e.subjectId}" to HwInfo(text = e.text, noHw = e.noHw, due = e.due, author = e.author, photos = e.photos.size)
            }).entries.toList().takeLast(200).associate { it.toPair() }) }
            // в архив ДЗ (вкладка «ДЗ», GitHub) — с уже загруженными ссылками на фото
            Archive.put(*ids.mapNotNull { cur(it) }.map { Archive.fromEntry(it) }.toTypedArray())
            if (entries.isNotEmpty()) Store.update { it.copy(mascot = it.mascot.copy(event = "hwSent", eventAt = System.currentTimeMillis())) }
            val keys = entries.map { "${it.lessonDate}|${it.subjectId}" }
            Store.update { s -> s.copy(sentKeys = (keys + s.sentKeys).distinct().take(300)) }
            runCatching { com.ilya.dzshka.link.MainLink.onSent(entries) }
            ids.forEach { Store.removeEntry(it, Store.d.send.deleteSentPhotos) }
            Store.saveNow()
            return summary.ifBlank { null }
        }

        override fun getForegroundInfo(): ForegroundInfo = Notif.sendingForeground(applicationContext)
    }
}
