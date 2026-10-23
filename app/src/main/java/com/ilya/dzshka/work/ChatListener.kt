package com.ilya.dzshka.work

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ilya.dzshka.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * «ДЗ из чата»: смотрит уведомления Viber. Если сегодня в выбранном чате пришло сообщение
 * с названием предмета, который сегодня есть в расписании, — считаем, что ДЗ по нему уже скинули:
 * напоминание после урока не приходит, в канал из приложения ничего не уходит.
 * Сохраняется только совпавшее сообщение, остальные никуда не записываются.
 */
class ChatListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in VIBER) return
        runCatching {
            Store.init(applicationContext)
            if (!Store.d.chatHw.enabled) return
            val ex = sbn.notification.extras
            val chat = listOfNotNull(
                ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
                ex.getCharSequence(Notification.EXTRA_TITLE),
                ex.getCharSequence(Notification.EXTRA_SUB_TEXT),
            ).map { it.toString().trim() }.filter { it.isNotBlank() }
            val msgs = messages(ex, sbn.postTime)
            handle(applicationContext, chat, msgs)
        }
    }

    companion object {
        val VIBER = setOf("com.viber.voip")

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, ChatListener::class.java).flattenToString()
            return flat.split(':').any { it.equals(me, true) }
        }

        fun askAccess(ctx: Context) = runCatching {
            ctx.startActivity(android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        /** Тексты сообщений с временем: из MessagingStyle, если есть, иначе обычный текст уведомления. */
        fun messages(ex: Bundle, postTime: Long): List<Pair<String, Long>> {
            @Suppress("DEPRECATION")
            val arr: Array<Parcelable>? = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            val fromStyle = arr?.mapNotNull { p ->
                (p as? Bundle)?.let { b -> b.getCharSequence("text")?.toString()?.let { it to b.getLong("time", postTime) } }
            }.orEmpty()
            if (fromStyle.isNotEmpty()) return fromStyle
            val t = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()
            return listOfNotNull(t?.let { it to postTime })
        }

        /** Корень слова для поиска: «Математика» → «математ», «Химия» → «хими». */
        fun stem(w: String): String {
            val s = w.lowercase().trim()
            return when {
                s.length >= 6 -> s.dropLast(2)
                s.length == 5 -> s.dropLast(1)
                else -> s
            }
        }

        fun keys(s: Subject): List<String> = buildList {
            add(s.name)
            if (s.short.isNotBlank()) add(s.short)
            s.name.split(' ').firstOrNull()?.takeIf { it.length >= 4 && it != s.name }?.let { add(it) }
        }.map { stem(it) }.filter { it.length >= 3 }.distinct()

        /** Где в тексте упомянут предмет: от начала слова до конца названия (с «язык», «яз.» и т.п.). */
        fun findMentions(text: String, s: Subject): List<IntRange> {
            val low = text.lowercase()
            val out = mutableListOf<IntRange>()
            for (k in keys(s)) {
                var i = low.indexOf(k)
                while (i >= 0) {
                    if (i == 0 || !low[i - 1].isLetterOrDigit()) {
                        var e = i + k.length
                        while (e < low.length && low[e].isLetter()) e++
                        // остальные слова названия: «Русский язык» → «рус. яз.», «русский язык»
                        for (w in s.name.lowercase().split(' ').drop(1).filter { it.isNotBlank() }) {
                            var j = e
                            while (j < low.length && (low[j] == '.' || low[j] == ' ')) j++
                            if (j + 2 <= low.length && low.startsWith(w.take(2), j)) {
                                e = j
                                while (e < low.length && low[e].isLetter()) e++
                            } else break
                        }
                        if (e < low.length && low[e] == '.') e++
                        out += i until e
                    }
                    i = low.indexOf(k, i + 1)
                }
            }
            // убрать вложенные (короткий ключ внутри длинного)
            return out.sortedWith(compareBy({ it.first }, { -it.last }))
                .fold(mutableListOf<IntRange>()) { acc, r -> if (acc.isNotEmpty() && r.first <= acc.last().last) acc else acc.apply { add(r) } }
        }

        /** Есть ли в тексте слово, начинающееся с корня предмета. */
        fun mentions(text: String, s: Subject): Boolean = findMentions(text, s).isNotEmpty()

        private val LEAD = Regex("""^[\s:;,.\-–—)\]>]+""")
        private val TAIL = Regex("""[\s:;,\-–—(\[<]+(по|на|и)?[\s:;,\-–—(\[<]*$""", RegexOption.IGNORE_CASE)

        /**
         * Текст ДЗ по предмету из сообщения: всё после названия предмета до следующего предмета.
         * «Математика: №238, 240\nХимия §3» → для математики «№238, 240».
         * Если после названия пусто («№5 по математике») — всё сообщение без названия.
         */
        fun extract(text: String, s: Subject, all: List<Subject>): String? {
            val mine = findMentions(text, s)
            if (mine.isEmpty()) return null
            val others = all.filter { it.id != s.id }.flatMap { findMentions(text, it) }
            val cuts = (mine + others).map { it.first }.sorted()
            for (m in mine) {
                val end = cuts.firstOrNull { it > m.last } ?: text.length
                val seg = clean(text.substring(m.last + 1, end))
                if (seg.isNotBlank()) return seg
            }
            val m = mine.first()
            return clean(text.removeRange(m)).ifBlank { text.trim() }
        }

        private fun clean(x: String): String {
            var t = x.trim()
            t = t.replace(LEAD, "")
            while (true) { val n = t.replace(TAIL, ""); if (n == t) break; t = n }
            if (t.lowercase().startsWith("по ")) t = t.drop(3)
            return t.trim()
        }

        fun handle(ctx: Context, chatTitles: List<String>, msgs: List<Pair<String, Long>>, today: LocalDate = LocalDate.now()) {
            val d = Store.d
            val cfg = d.chatHw
            val title = chatTitles.firstOrNull() ?: ""
            // запоминаем, какие чаты бывают, чтобы выбрать нужный в настройках
            if (title.isNotBlank() && title !in cfg.seen) Store.update { it.copy(chatHw = it.chatHw.copy(seen = (listOf(title) + it.chatHw.seen).take(15))) }
            // читаем только канал, чей токен вставлен в настройках Viber
            val channel = d.viber.channelName.trim()
            if (channel.isBlank() || chatTitles.none { it.contains(channel, ignoreCase = true) }) return
            val zone = ZoneId.systemDefault()
            val todays = msgs.filter { Instant.ofEpochMilli(it.second).atZone(zone).toLocalDate() == today }.map { it.first }
            if (todays.isEmpty()) return
            val lessons = Sched.lessonsOn(d, today)
            val found = mutableMapOf<String, Pair<Subject, String>>()
            for (l in lessons) {
                val s = d.subject(l.subjectId) ?: continue
                val key = "$today|${s.id}"
                if (key in d.fromChat || key in found) continue
                // уже записано в приложении или отправлено — не трогаем
                if (d.isSent(s.id, today.toString()) || d.queue.any { it.subjectId == s.id && it.lessonDate == today.toString() }) continue
                for (m in todays) {
                    val hw = extract(m, s, d.subjects) ?: continue
                    found[key] = s to hw.take(1000)
                    break
                }
            }
            if (found.isEmpty()) return
            // как будто отправлено из приложения: отметка «отправлено», текст ДЗ, на какое число, история
            Store.update { a ->
                val info = found.mapValues { (_, v) ->
                    HwInfo(text = v.second, due = Sched.dueFor(a, a.dueMode, v.first.id, today).toString())
                }
                a.copy(
                    fromChat = (a.fromChat + found.mapValues { it.value.second }).entries.toList().takeLast(200).associate { it.toPair() },
                    sentKeys = (found.keys.toList() + a.sentKeys).distinct().take(300),
                    hwLog = (a.hwLog + found.mapValues { it.value.second }).entries.toList().takeLast(200).associate { it.toPair() },
                    hwInfo = (a.hwInfo + info).entries.toList().takeLast(200).associate { it.toPair() },
                )
            }
            found.values.forEach { (s, hw) -> Store.addHistory(SentRecord(text = "${s.name}: $hw", ok = true)) }
            Archive.put(*found.map { (key, v) ->
                HwRecord(id = key, subjectId = v.first.id, subjectName = v.first.name, text = v.second, lessonDate = today.toString(),
                    due = Sched.dueFor(Store.d, Store.d.dueMode, v.first.id, today).toString(), source = "chat")
            }.toTypedArray())
            Store.saveNow()
            // если напоминание по этому уроку уже висит — убрать
            runCatching {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                lessons.filter { "$today|${it.subjectId}" in found }.forEach { l -> nm.cancel(Notif.lessonNotifId(today.toString(), l.index)) }
            }
        }
    }
}
