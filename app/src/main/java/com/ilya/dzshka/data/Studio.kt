package com.ilya.dzshka.data

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime

enum class HeaderDate(val label: String) {
    TODAY("Сегодня (день, когда задали)"),
    NEXT_SCHOOL_DAY("Следующий учебный день"),
}

/** Вид сообщения в студии. */
enum class StepKind(val label: String, val short: String, val usesEntries: Boolean) {
    DAY_HEADER("Заголовок дня", "📅 Заголовок", false),
    TEXT("Свой текст", "💬 Свой текст", false),
    HW_TEXT("Каждое ДЗ — отдельным сообщением", "📝 Каждое ДЗ", true),
    HW_WITH_PHOTO("Каждое ДЗ — текст подписью к фото", "🖼 ДЗ + фото", true),
    HW_PHOTOS("Фото каждого ДЗ — отдельно", "📷 Фото ДЗ", true),
    ALL_TEXT("Все ДЗ — одним сообщением", "📋 Все ДЗ списком", true),
    ALL_PHOTOS("Все фото подряд", "🗂 Все фото", true),
}

enum class Trigger(val label: String) {
    ON_ADD("Сразу после ввода ДЗ"),
    FIRST_LESSON("К началу первого урока"),
    AFTER_LAST("После последнего урока"),
    AT_TIME("В заданное время"),
    MANUAL("Только кнопкой"),
}

@Serializable
data class Step(
    val id: String = newId(),
    val kind: StepKind = StepKind.HW_TEXT,
    /** текст сообщения; для «Все ДЗ списком» — заголовок списка */
    val template: String = "",
    val line: String = "Э П: Т",
    val separator: String = "\n",
    val footer: String = "",
    val groupGap: String = "\n\n",
    val caption: String = "",
    val skipNoHw: Boolean = false,
    val groupByDate: Boolean = true,
    val onlyIfHw: Boolean = true,
    val onceADay: Boolean = true,
    val dateKind: HeaderDate = HeaderDate.TODAY,
) {
    companion object {
        fun of(kind: StepKind): Step = when (kind) {
            StepKind.DAY_HEADER -> Step(kind = kind, template = "📅 ДЗ за Ч М (Д)", onlyIfHw = false)
            StepKind.TEXT -> Step(kind = kind, template = "Всем удачи! 💪", onceADay = false)
            StepKind.HW_TEXT -> Step(kind = kind, template = "Э П: Т")
            StepKind.HW_WITH_PHOTO -> Step(kind = kind, template = "Э П: Т")
            StepKind.HW_PHOTOS -> Step(kind = kind, caption = "Э П")
            StepKind.ALL_TEXT -> Step(kind = kind, template = "📅 ДЗ на Ч М, Д", line = "Э П: Т")
            StepKind.ALL_PHOTOS -> Step(kind = kind, caption = "Э П — ДЗ на Ч М")
        }
    }
}

@Serializable
data class Moment(
    val id: String = newId(),
    val name: String = "",
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger.ON_ADD,
    val time: String = "15:00",
    val offsetMin: Int = 0,
    val onlySchoolDays: Boolean = true,
    val steps: List<Step> = emptyList(),
) {
    val hasHwSteps: Boolean get() = steps.any { it.kind.usesEntries }
}

class Preset(val name: String, val desc: String, val build: () -> List<Moment>)

object Presets {
    private fun header() = Step.of(StepKind.DAY_HEADER)

    val list = listOf(
        Preset("Заголовок утром + каждое ДЗ отдельно",
            "К первому уроку: «📅 ДЗ за 16 октября». Потом каждое ДЗ сразу отдельным сообщением.") {
            listOf(
                Moment(name = "Утро", trigger = Trigger.FIRST_LESSON, steps = listOf(header())),
                Moment(name = "Каждое ДЗ", trigger = Trigger.ON_ADD, steps = listOf(header().copy(onlyIfHw = true), Step.of(StepKind.HW_WITH_PHOTO))),
            )
        },
        Preset("Каждое ДЗ сразу, с датой",
            "Одно сообщение на предмет: «📚 ДЗ на 19 октября (понедельник) / Математика: №238».") {
            listOf(Moment(name = "Каждое ДЗ", trigger = Trigger.ON_ADD,
                steps = listOf(Step.of(StepKind.HW_WITH_PHOTO).copy(template = "📚 ДЗ на Ч М (Д)\nЭ П: Т"))))
        },
        Preset("Всё одним сообщением после уроков",
            "Через 10 минут после последнего урока — один пост со всеми ДЗ, потом фото.") {
            listOf(Moment(name = "После уроков", trigger = Trigger.AFTER_LAST, offsetMin = 10,
                steps = listOf(Step.of(StepKind.ALL_TEXT), Step.of(StepKind.ALL_PHOTOS))))
        },
        Preset("Всё по отдельности после уроков",
            "После уроков: заголовок, затем каждое ДЗ своим сообщением, затем фото.") {
            listOf(Moment(name = "После уроков", trigger = Trigger.AFTER_LAST, offsetMin = 10,
                steps = listOf(header().copy(onlyIfHw = true), Step.of(StepKind.HW_TEXT), Step.of(StepKind.HW_PHOTOS))))
        },
        Preset("Сводка вечером в 18:00",
            "Все ДЗ за день одним сообщением в 18:00.") {
            listOf(Moment(name = "Вечер", trigger = Trigger.AT_TIME, time = "18:00",
                steps = listOf(Step.of(StepKind.ALL_TEXT), Step.of(StepKind.ALL_PHOTOS))))
        },
        Preset("Только вручную",
            "Копится в очереди, уходит одним сообщением, когда нажмёшь «Отправить».") {
            listOf(Moment(name = "По кнопке", trigger = Trigger.MANUAL,
                steps = listOf(Step.of(StepKind.ALL_TEXT), Step.of(StepKind.ALL_PHOTOS))))
        },
    )
}

object Studio {
    const val ONCE_HEADER = "header"

    fun onAddMoment(d: AppData): Moment? = d.moments.firstOrNull { it.enabled && it.trigger == Trigger.ON_ADD && it.hasHwSteps }

    /** Каким моментом отправлять очередь по кнопке. */
    fun queueMoment(d: AppData): Moment =
        d.moments.firstOrNull { it.enabled && it.trigger != Trigger.ON_ADD && it.hasHwSteps }
            ?: onAddMoment(d)
            ?: Moment(id = "fallback", name = "Обычная отправка", trigger = Trigger.MANUAL, steps = listOf(Step.of(StepKind.HW_WITH_PHOTO)))

    /** Каким моментом отправлять одну запись «прямо сейчас». */
    fun nowMoment(d: AppData): Moment = onAddMoment(d) ?: queueMoment(d)

    fun findMoment(d: AppData, id: String): Moment? =
        d.moments.firstOrNull { it.id == id } ?: if (id == "fallback") queueMoment(d) else null

    private fun sorted(es: List<HwEntry>) = es.sortedWith(compareBy({ it.lessonDate }, { it.lessonNum }, { it.created }))

    fun plan(d: AppData, steps: List<Step>, entries: List<HwEntry>, today: LocalDate, sentOnce: Map<String, String>): List<Post> {
        val es = sorted(entries)
        val out = mutableListOf<Post>()
        val once = sentOnce.toMutableMap()
        val ids = es.map { it.id }
        val max = d.viber.maxTextLen
        for (st in steps) {
            val list = if (st.skipNoHw) es.filterNot { it.noHw } else es
            when (st.kind) {
                StepKind.DAY_HEADER, StepKind.TEXT -> {
                    if (st.onlyIfHw && es.isEmpty()) continue
                    val onceKey = if (st.kind == StepKind.DAY_HEADER) ONCE_HEADER else "text:${st.id}"
                    if (st.onceADay && once[onceKey] == today.toString()) continue
                    val text = Builder.r(d, st.template, Builder.dayCtx(d, st.dateKind, today))
                    if (text.isBlank()) continue
                    Builder.split(text, max).forEachIndexed { i, part ->
                        out += Post.Text(part, "${st.id}:$i", ids, onceKey.takeIf { st.onceADay })
                    }
                    if (st.onceADay) once[onceKey] = today.toString()
                }
                StepKind.HW_TEXT -> list.forEach { e ->
                    val text = Builder.r(d, st.template, Builder.ctx(d, e, today))
                    if (text.isNotBlank()) Builder.split(text, max).forEachIndexed { i, part ->
                        out += Post.Text(part, "${st.id}:${e.id}:$i", listOf(e.id))
                    }
                }
                StepKind.HW_WITH_PHOTO -> list.forEach { e ->
                    val c = Builder.ctx(d, e, today)
                    val text = Builder.r(d, st.template, c)
                    val cap = Builder.r(d, st.caption, c).take(768)
                    if (e.photos.isEmpty()) {
                        if (text.isNotBlank()) Builder.split(text, max).forEachIndexed { i, part ->
                            out += Post.Text(part, "${st.id}:${e.id}:$i", listOf(e.id))
                        }
                    } else if (text.length <= 768) {
                        e.photos.forEachIndexed { i, f -> out += Post.Photo(e.id, f, if (i == 0) text else cap, "${st.id}:$f") }
                    } else {
                        Builder.split(text, max).forEachIndexed { i, part -> out += Post.Text(part, "${st.id}:${e.id}:$i", listOf(e.id)) }
                        e.photos.forEach { f -> out += Post.Photo(e.id, f, cap, "${st.id}:$f") }
                    }
                }
                StepKind.HW_PHOTOS, StepKind.ALL_PHOTOS -> list.forEach { e ->
                    val cap = Builder.r(d, st.caption, Builder.ctx(d, e, today)).take(768)
                    e.photos.forEach { f -> out += Post.Photo(e.id, f, cap, "${st.id}:$f") }
                }
                StepKind.ALL_TEXT -> {
                    if (list.isEmpty()) continue
                    val text = listText(d, st, list, today)
                    if (text.isNotBlank()) Builder.split(text, max).forEachIndexed { i, part ->
                        out += Post.Text(part, "${st.id}:$i", list.map { it.id })
                    }
                }
            }
        }
        return out
    }

    fun listText(d: AppData, st: Step, list: List<HwEntry>, today: LocalDate): String {
        val ordered = list.sortedWith(compareBy({ it.due }, { it.lessonDate }, { it.lessonNum }))
        val groups = if (st.groupByDate) ordered.groupBy { it.due }.values.toList() else listOf(ordered)
        val sep = Tpl.render(st.separator, d, TplCtx())
        val gap = Tpl.render(st.groupGap, d, TplCtx())
        return groups.joinToString(gap) { g ->
            val first = Builder.ctx(d, g.first(), today)
            val parts = mutableListOf<String>()
            Builder.r(d, st.template, first).takeIf { it.isNotBlank() }?.let { parts += it }
            parts += g.joinToString(sep) { Builder.r(d, st.line, Builder.ctx(d, it, today)) }
            Builder.r(d, st.footer, first).takeIf { it.isNotBlank() }?.let { parts += it }
            parts.joinToString("\n")
        }
    }

    /** Когда сработает момент в следующий раз (null — не по времени). */
    /** Во сколько момент сработает в этот день (null — не в этот день / не по времени). */
    fun fireOn(d: AppData, m: Moment, date: LocalDate): LocalDateTime? {
        if (!m.enabled) return null
        val lessons = Sched.lessonsOn(d, date)
        return when (m.trigger) {
            Trigger.FIRST_LESSON -> lessons.firstNotNullOfOrNull { it.start }
                ?.let { date.atStartOfDay().plusMinutes((it + m.offsetMin).toLong()) }
            Trigger.AFTER_LAST -> lessons.mapNotNull { it.end }.maxOrNull()
                ?.let { date.atStartOfDay().plusMinutes((it + m.offsetMin).toLong()) }
            Trigger.AT_TIME -> if (m.onlySchoolDays && lessons.isEmpty()) null
                else Time.parse(m.time)?.let { date.atStartOfDay().plusMinutes(it.toLong()) }
            else -> null
        }
    }

    /** Когда сработает момент в следующий раз (null — не по времени). */
    fun nextFire(d: AppData, m: Moment, from: LocalDateTime): LocalDateTime? {
        if (!m.enabled || m.trigger == Trigger.ON_ADD || m.trigger == Trigger.MANUAL) return null
        for (k in 0..14) {
            val t = fireOn(d, m, from.toLocalDate().plusDays(k.toLong()))
            if (t != null && t.isAfter(from)) return t
        }
        return null
    }

    fun whenText(m: Moment): String = when (m.trigger) {
        Trigger.ON_ADD -> "сразу после ввода ДЗ"
        Trigger.FIRST_LESSON -> "к первому уроку" + offsetText(m.offsetMin)
        Trigger.AFTER_LAST -> "после последнего урока" + offsetText(m.offsetMin)
        Trigger.AT_TIME -> "в ${m.time}" + if (m.onlySchoolDays) " (учебные дни)" else " (каждый день)"
        Trigger.MANUAL -> "по кнопке «Отправить»"
    }

    private fun offsetText(o: Int) = when {
        o > 0 -> " + $o мин"
        o < 0 -> " − ${-o} мин"
        else -> ""
    }

    // ---------- предпросмотр дня ----------

    data class SimEvent(val time: String, val title: String, val post: Post)

    /** Проигрывает типичный день: ДЗ вводятся после своих уроков, моменты срабатывают по времени. */
    fun simulate(d: AppData, date: LocalDate, entries: List<HwEntry>, lessonEnds: Map<String, Int>): List<SimEvent> {
        data class Ev(val minute: Int, val order: Int, val title: String, val moment: Moment?, val entry: HwEntry?)
        val lessons = Sched.lessonsOn(d, date)
        val first = lessons.firstNotNullOfOrNull { it.start } ?: (8 * 60 + 30)
        val last = lessons.mapNotNull { it.end }.maxOrNull() ?: (14 * 60)
        val evs = mutableListOf<Ev>()
        val onAdd = onAddMoment(d)
        entries.forEachIndexed { i, e -> evs += Ev(lessonEnds[e.id] ?: (first + 45 * (i + 1)), 1, "ввёл ДЗ: ${e.subjectName}", onAdd, e) }
        d.moments.filter { it.enabled }.forEach { m ->
            when (m.trigger) {
                Trigger.FIRST_LESSON -> evs += Ev(first + m.offsetMin, 0, m.name.ifBlank { "к первому уроку" }, m, null)
                Trigger.AFTER_LAST -> evs += Ev(last + m.offsetMin, 2, m.name.ifBlank { "после уроков" }, m, null)
                Trigger.AT_TIME -> evs += Ev(Time.parse(m.time) ?: (18 * 60), 2, m.name.ifBlank { m.time }, m, null)
                Trigger.MANUAL -> evs += Ev(24 * 60 + 1, 3, "по кнопке «Отправить»", m, null)
                Trigger.ON_ADD -> {}
            }
        }
        val out = mutableListOf<SimEvent>()
        val once = mutableMapOf<String, String>()
        val queue = mutableListOf<HwEntry>()
        for (ev in evs.sortedWith(compareBy({ it.minute }, { it.order }))) {
            val tLabel = if (ev.minute > 24 * 60) "—" else Time.fmt(ev.minute)
            val m = ev.moment
            val forEntries: List<HwEntry> = when {
                ev.entry != null && m == null -> { queue += ev.entry; continue }
                ev.entry != null -> listOf(ev.entry)
                m != null && m.hasHwSteps -> queue.toList().also { queue.clear() }
                else -> emptyList()
            }
            val posts = plan(d, m!!.steps, forEntries, date, once)
            posts.filterIsInstance<Post.Text>().mapNotNull { it.onceKey }.forEach { once[it] = date.toString() }
            posts.forEach { out += SimEvent(tLabel, ev.title, it) }
        }
        return out
    }
}
