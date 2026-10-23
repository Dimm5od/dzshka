package com.ilya.dzshka.data

import java.time.LocalDate

/** Архив ДЗ: всё, что задали, — для вкладки «ДЗ», виджета и GitHub. */
object Archive {
    fun id(lessonDate: String, subjectId: String) = "$lessonDate|$subjectId"

    /** Как ДЗ выглядит в календаре: запись архива или запись из очереди (ещё не отправлена). */
    data class Item(val rec: HwRecord, val pending: Boolean = false) {
        val day: LocalDate? get() = Sched.parseDate(rec.due) ?: Sched.parseDate(rec.lessonDate)
    }

    fun merge(list: List<HwRecord>, add: List<HwRecord>): List<HwRecord> {
        val m = LinkedHashMap<String, HwRecord>()
        (list + add).forEach { r ->
            val old = m[r.id]
            if (old == null || r.updated >= old.updated) m[r.id] = r
        }
        return m.values.sortedWith(compareBy({ it.lessonDate }, { it.subjectName })).takeLast(4000)
    }

    fun put(vararg recs: HwRecord) {
        if (recs.isEmpty()) return
        Store.update { a -> a.copy(archive = merge(a.archive, recs.toList()), gh = a.gh.copy(dirty = true)) }
    }

    fun remove(lessonDate: String, subjectId: String) {
        val key = id(lessonDate, subjectId)
        Store.update { a ->
            val old = a.archive.firstOrNull { it.id == key } ?: return@update a
            a.copy(archive = merge(a.archive, listOf(old.copy(deleted = true, updated = System.currentTimeMillis()))), gh = a.gh.copy(dirty = true))
        }
    }

    fun fromEntry(e: HwEntry, source: String = if (e.helperId.isNotBlank()) "helper" else "app"): HwRecord = HwRecord(
        id = id(e.lessonDate, e.subjectId.ifBlank { "x:" + e.subjectName }),
        subjectId = e.subjectId,
        subjectName = e.subjectName,
        text = e.text,
        none = e.noHw,
        lessonDate = e.lessonDate,
        due = e.due,
        author = e.author,
        source = source,
        photos = e.photos.mapNotNull { e.uploaded[it] },
    )

    /** Всё, что показываем: архив + очередь (очередь главнее — там свежее). */
    fun items(d: AppData): List<Item> {
        val pend = d.queue.filter { it.status != EntryStatus.APPROVAL }.map { Item(fromEntry(it), pending = true) }
        val keys = pend.map { it.rec.id }.toSet()
        return d.archive.filter { !it.deleted && it.id !in keys }.map { Item(it) } + pend
    }

    fun subjectOf(d: AppData, r: HwRecord): Subject? = d.subject(r.subjectId)

    fun label(d: AppData, r: HwRecord): String =
        if (r.none) "не задали" else r.text.ifBlank { if (r.photos.isNotEmpty()) "📷 фото" else "—" }

    /** Миграция: собрать архив из того, что уже было отправлено/записано раньше. */
    fun seed(d: AppData): List<HwRecord> {
        val out = mutableListOf<HwRecord>()
        val keys = (d.sentKeys + d.hwInfo.keys + d.skipped).distinct()
        for (k in keys) {
            val date = k.substringBefore("|")
            val sid = k.substringAfter("|")
            val ld = Sched.parseDate(date) ?: continue
            val s = d.subject(sid)
            val inf = d.hwInfo[k]
            val skipped = k in d.skipped
            val text = inf?.text ?: d.hwLog[k] ?: ""
            if (!skipped && text.isBlank()) continue
            out += HwRecord(
                id = k, subjectId = sid, subjectName = s?.name ?: "",
                text = if (skipped) "" else text, none = skipped || inf?.noHw == true,
                lessonDate = date, due = inf?.due ?: Sched.dueFor(d, d.dueMode, sid, ld).toString(),
                author = inf?.author ?: "", source = if (k in d.fromChat) "chat" else "app",
            )
        }
        return out
    }
}
