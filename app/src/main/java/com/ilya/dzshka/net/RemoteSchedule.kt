package com.ilya.dzshka.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ilya.dzshka.data.AppData
import com.ilya.dzshka.data.Store
import com.ilya.dzshka.data.Subject
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.util.concurrent.Executors

/**
 * Расписание через GitHub: файл schedule.json рядом с update.json.
 * Если в файле rev больше, чем уже применённый, — дни из файла записываются в расписание.
 * Остальные дни и всё, что поменяли руками после этого, не трогаются.
 */
object RemoteSchedule {
    const val URL = Updater.BASE + "schedule.json"

    @Serializable
    data class RsLesson(
        val name: String = "",
        /** другие написания, по которым искать уже существующий предмет */
        val match: List<String> = emptyList(),
        val short: String = "",
        val emoji: String = "",
        /** переименовать найденный предмет в name (и поставить short/emoji) */
        val rename: Boolean = false,
    )

    /** day: 0 = понедельник … 6 = воскресенье; week: -1 = во все недели. Пустое name — окно. */
    @Serializable
    data class RsDay(val day: Int, val week: Int = -1, val lessons: List<RsLesson> = emptyList())

    @Serializable
    data class RsFile(val rev: Int = 0, val note: String = "", val days: List<RsDay> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val pool = Executors.newSingleThreadExecutor()
    @Volatile private var lastCheck = 0L

    fun parse(text: String): RsFile = json.decodeFromString(RsFile.serializer(), text)

    fun fetch(url: String = URL): RsFile? {
        val req = Request.Builder().url(url + "?t=" + System.currentTimeMillis() / 60_000).header("Cache-Control", "no-cache").build()
        Viber.http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw Exception("GitHub ответил ${r.code}")
            return parse(r.body!!.string())
        }
    }

    internal fun norm(s: String) = s.lowercase().replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    internal fun find(subjects: List<Subject>, l: RsLesson): Subject? {
        val keys = (l.match + l.name).map(::norm).filter { it.isNotBlank() }
        if (keys.isEmpty()) return null
        subjects.firstOrNull { s -> norm(s.name) in keys || (s.short.isNotBlank() && norm(s.short) in keys) }?.let { return it }
        return subjects.firstOrNull { s -> val n = norm(s.name); keys.any { k -> k.length >= 5 && n.startsWith(k) } }
    }

    private val WD = listOf("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье")

    /** Применить файл к данным. Возвращает новые данные и короткий отчёт. */
    fun apply(d: AppData, f: RsFile): Pair<AppData, String> {
        var subjects = d.subjects
        var weeks = d.weeks
        var created = 0
        val parts = mutableListOf<String>()
        for (day in f.days) {
            if (day.day !in 0..6) continue
            val ids = day.lessons.map { l ->
                if (l.name.isBlank()) return@map ""
                val ex = find(subjects, l)
                if (ex != null) {
                    if (l.rename) {
                        val upd = ex.copy(name = l.name.trim(), short = l.short, emoji = l.emoji.ifBlank { ex.emoji })
                        subjects = subjects.map { if (it.id == ex.id) upd else it }
                    }
                    ex.id
                } else {
                    val s = Subject(name = l.name.trim(), short = l.short, emoji = l.emoji)
                    subjects = subjects + s
                    created++
                    s.id
                }
            }.dropLastWhile { it.isBlank() }
            weeks = weeks.mapIndexed { wi, w ->
                if (day.week >= 0 && wi != day.week) w
                else w.copy(days = w.days.mapIndexed { di, ds -> if (di == day.day) ds.copy(enabled = true, lessons = ids) else ds })
            }
            parts += "${WD[day.day]}: ${ids.count { it.isNotBlank() }} ур."
        }
        val report = buildString {
            append(parts.joinToString(", ").ifBlank { "без изменений" })
            if (created > 0) append(" · новых предметов: $created")
            if (f.note.isNotBlank()) append(" · ").append(f.note)
        }
        val at = java.time.LocalDateTime.now().withNano(0).toString().replace('T', ' ')
        return d.copy(subjects = subjects, weeks = weeks, remoteSched = d.remoteSched.copy(rev = f.rev, report = report, at = at)) to report
    }

    /** Проверить GitHub в фоне. force — даже если проверяли недавно (кнопка). */
    fun check(ctx: Context, force: Boolean = false, onDone: ((String) -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (!force && (!Store.d.remoteSched.enabled || now - lastCheck < 10 * 60_000)) return
        lastCheck = now
        val app = ctx.applicationContext
        pool.execute {
            val msg = runCatching {
                val f = fetch() ?: return@runCatching "На GitHub нет расписания"
                if (f.rev <= Store.d.remoteSched.rev) return@runCatching "Новых изменений нет"
                var rep = ""
                Store.update { d -> apply(d, f).also { rep = it.second }.first }
                Store.saveNow()
                "Расписание обновлено: $rep"
            }.getOrElse { "Не получилось: ${Viber.netError(it) ?: it.message}" }
            Handler(Looper.getMainLooper()).post {
                if (force || msg.startsWith("Расписание обновлено")) Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                onDone?.invoke(msg)
            }
        }
    }
}
