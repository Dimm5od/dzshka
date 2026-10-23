package com.ilya.dzshka.work

import android.content.Context
import com.ilya.dzshka.data.*
import com.ilya.dzshka.net.Viber
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** «Неделя за N минут»: прокручивает расписание и студию на виртуальных часах. Настоящую очередь не трогает. */
object TestRun {
    data class Cfg(
        val minutes: Int = 10,
        val nextWeek: Boolean = false,
        val notifications: Boolean = true,
        val autoFill: Boolean = true,
        val realSend: Boolean = false,
    )

    enum class Kind { LESSON_END, MOMENT, MANUAL }

    data class Ev(val time: LocalDateTime, val kind: Kind, val title: String, val lesson: Lesson? = null, val moment: Moment? = null)

    data class LogLine(val time: LocalDateTime, val text: String)

    data class State(
        val running: Boolean = false,
        val finished: Boolean = false,
        val cfg: Cfg = Cfg(),
        val vnow: LocalDateTime? = null,
        val index: Int = 0,
        val total: Int = 0,
        val current: Ev? = null,
        val waiting: Boolean = false,
        val slotEndsAt: Long = 0,
        val posts: List<Pair<LocalDateTime, Studio.SimEvent>> = emptyList(),
        val log: List<LogLine> = emptyList(),
        val entries: Int = 0,
        val realSent: Int = 0,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList(),
        val noHwCount: Int = 0,
    )

    sealed class Answer {
        data class Text(val text: String) : Answer()
        data object NoHw : Answer()
        data object Auto : Answer()
        data object Skip : Answer()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var answers = Channel<Answer>(Channel.CONFLATED)

    const val NOTIF_ID = 0x7E57

    private val samples = listOf(
        "№ 238, 240", "стр. 45, упр. 3", "§12, вопросы 1–4", "выучить правило на стр. 30", "читать гл. 5",
        "конспект параграфа 7", "№ 1–5 (чётные)", "повторить таблицу", "сочинение на 1 страницу", "упр. 112",
    )

    // ---------- события недели (чистая функция, тестируется отдельно) ----------

    fun weekStart(today: LocalDate, next: Boolean): LocalDate =
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(if (next) 1 else 0)

    fun buildEvents(d0: AppData, monday: LocalDate): List<Ev> {
        // в тесте «звонки» есть всегда, даже если напоминания выключены в настройках
        val d = d0.copy(reminder = d0.reminder.copy(enabled = true))
        val out = mutableListOf<Ev>()
        val manual = Studio.queueMoment(d).takeIf { it.trigger == Trigger.MANUAL && it.enabled }
        for (k in 0..6) {
            val date = monday.plusDays(k.toLong())
            Sched.reminders(d, date.atStartOfDay(), days = 0).forEach { r ->
                out += Ev(r.time, Kind.LESSON_END, "Урок закончился: ${d.subject(r.lesson.subjectId)?.name ?: "?"}", lesson = r.lesson)
            }
            d.moments.filter { it.enabled }.forEach { m ->
                Studio.fireOn(d, m, date)?.let { out += Ev(it, Kind.MOMENT, "Момент «${m.name.ifBlank { Studio.whenText(m) }}»", moment = m) }
            }
            if (manual != null && Sched.isSchoolDay(d, date)) out += Ev(date.atTime(20, 0), Kind.MANUAL, "Нажал «Отправить» на главной", moment = manual)
        }
        return out.sortedWith(compareBy({ it.time }, { if (it.kind == Kind.LESSON_END) 0 else 1 }))
    }

    // ---------- управление ----------

    fun answer(a: Answer) { answers.trySend(a) }

    fun stop(ctx: Context) {
        job?.cancel()
        job = null
        Notif.cancel(ctx, NOTIF_ID)
        _state.value = _state.value.copy(running = false, waiting = false)
    }

    fun reset() {
        if (job?.isActive == true) return
        _state.value = State()
    }

    fun start(ctx: Context, cfg: Cfg) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        val d = Store.d
        val monday = weekStart(LocalDate.now(), cfg.nextWeek)
        val events = buildEvents(d, monday)
        answers = Channel(Channel.CONFLATED)
        _state.value = State(running = true, cfg = cfg, total = events.size, vnow = monday.atStartOfDay())
        if (events.isEmpty()) {
            _state.value = _state.value.copy(running = false, finished = true,
                errors = listOf("В этой неделе нет ни одного урока со временем звонков — заполни расписание и звонки."))
            return
        }
        // вес: на звонок даём больше времени (успеть ответить), на моменты — меньше
        val weights = events.map { if (it.kind == Kind.LESSON_END) 3 else 1 }
        val unitMs = cfg.minutes.coerceIn(1, 60) * 60_000L / weights.sum()

        job = scope.launch {
            val queue = mutableListOf<HwEntry>()
            val once = mutableMapOf<String, String>()
            var rnd = 0
            try {
                for ((i, ev) in events.withIndex()) {
                    val slot = unitMs * weights[i]
                    val date = ev.time.toLocalDate()
                    update { it.copy(vnow = ev.time, index = i, current = ev) }
                    when (ev.kind) {
                        Kind.LESSON_END -> {
                            val l = ev.lesson!!
                            val subj = d.subject(l.subjectId)
                            while (answers.tryReceive().isSuccess) { /* выбросить старые ответы */ }
                            update { it.copy(waiting = true, slotEndsAt = System.currentTimeMillis() + slot) }
                            // напоминание — как в настройках: сообщение или экранчик
                            if (cfg.notifications) withContext(Dispatchers.Main) {
                                Notif.remind(app, l.date, l.index, l.number, l.subjectId, test = true, testIndex = i)
                            }
                            val a = withTimeoutOrNull(slot) { answers.receive() }
                            update { it.copy(waiting = false) }
                            Notif.cancel(app, NOTIF_ID)
                            val auto = a == Answer.Auto || (a == null && cfg.autoFill)
                            val entry: HwEntry? = when {
                                a is Answer.Text && a.text.isNotBlank() -> Entries.create(d, l.subjectId, subj?.name ?: "", date, l.number, a.text.trim())
                                a == Answer.NoHw -> Entries.create(d, l.subjectId, subj?.name ?: "", date, l.number, "", noHw = true)
                                auto -> {
                                    rnd++
                                    if (rnd % 5 == 0) Entries.create(d, l.subjectId, subj?.name ?: "", date, l.number, "", noHw = true)
                                    else Entries.create(d, l.subjectId, subj?.name ?: "", date, l.number, samples[(rnd * 7 + l.index) % samples.size])
                                }
                                else -> null
                            }
                            if (entry == null) log(ev.time, "${subj?.name}: пропустил, ДЗ не записано")
                            else {
                                log(ev.time, "${entry.subjectName}: " + (if (entry.noHw) "нет ДЗ" else entry.text) + (if (auto) " (придумал сам)" else "") + " → на ${pretty(d, entry.due)}")
                                update { it.copy(entries = it.entries + 1, noHwCount = it.noHwCount + if (entry.noHw) 1 else 0) }
                                val onAdd = Studio.onAddMoment(d)
                                if (onAdd != null) emit(app, ev.time, "ввёл ДЗ: ${entry.subjectName}", Studio.plan(d, onAdd.steps, listOf(entry), date, once), once, date, cfg)
                                else { queue += entry; log(ev.time, "в очереди: ${queue.size}") }
                            }
                        }
                        Kind.MOMENT, Kind.MANUAL -> {
                            val m = ev.moment!!
                            val list = if (m.hasHwSteps) queue.toList() else emptyList()
                            val posts = Studio.plan(d, m.steps, list, date, once)
                            if (posts.isEmpty()) log(ev.time, "${ev.title}: отправлять нечего")
                            emit(app, ev.time, ev.title.removePrefix("Момент "), posts, once, date, cfg)
                            if (m.hasHwSteps) queue.clear()
                            delay(slot)
                        }
                    }
                }
                if (queue.isNotEmpty()) {
                    val w = "В очереди осталось ${queue.size} ДЗ — ни один момент их не забрал. Добавь в студии момент, который отправляет ДЗ."
                    log(events.last().time, w)
                    update { it.copy(warnings = it.warnings + w) }
                }
                val st = _state.value
                val ws = mutableListOf<String>()
                if (st.posts.isEmpty()) ws += "За неделю в канал не ушло ни одного сообщения."
                val perDay = st.posts.groupBy { it.first.toLocalDate() }
                perDay.forEach { (day, l) -> if (l.size > 15) ws += "${pretty(d, day.toString())}: ${l.size} сообщений — многовато, может, лучше сводкой?" }
                if (ws.isNotEmpty()) update { it.copy(warnings = it.warnings + ws) }
                update { it.copy(running = false, finished = true, current = null, waiting = false) }
            } catch (e: CancellationException) {
                update { it.copy(running = false, waiting = false) }
                throw e
            }
        }
    }

    private fun pretty(d: AppData, iso: String): String {
        val x = Sched.parseDate(iso) ?: return iso
        return "${d.weekdaysShort.getOrElse(x.dayOfWeek.value - 1) { "" }}, ${x.dayOfMonth} ${d.monthsA.getOrElse(x.monthValue - 1) { "" }}"
    }

    private fun update(f: (State) -> State) { _state.value = f(_state.value) }

    private fun log(t: LocalDateTime, s: String) = update { it.copy(log = it.log + LogLine(t, s)) }

    private suspend fun emit(ctx: Context, t: LocalDateTime, title: String, posts: List<Post>, once: MutableMap<String, String>, date: LocalDate, cfg: Cfg) {
        posts.filterIsInstance<Post.Text>().mapNotNull { it.onceKey }.forEach { once[it] = date.toString() }
        val tl = Time.fmt(t.hour * 60 + t.minute)
        update { s -> s.copy(posts = s.posts + posts.map { t to Studio.SimEvent(tl, title, it) }) }
        if (!cfg.realSend) return
        val v = Store.d.let { if (it.testReady) it.testViber else it.viber }
        withContext(Dispatchers.IO) {
            for (p in posts) {
                val text = when (p) {
                    is Post.Text -> p.text
                    is Post.Photo -> continue // в тесте фото нет
                }
                runCatching { Viber.postText(v.token, v.fromId, "🧪 " + text, v.minApi) }
                    .onSuccess { update { it.copy(realSent = it.realSent + 1) } }
                    .onFailure { e -> update { it.copy(errors = it.errors + (e.message ?: "ошибка")) } }
            }
        }
    }
}
