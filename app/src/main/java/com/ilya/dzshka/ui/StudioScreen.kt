@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.data.*
import java.time.LocalDate

/** Примерные ДЗ для предпросмотра: по урокам ближайшего учебного дня. */
fun sampleDay(d: AppData): Triple<LocalDate, List<HwEntry>, Map<String, Int>> {
    val today = LocalDate.now()
    val date = if (Sched.isSchoolDay(d, today)) today else Sched.nextSchoolDay(d, today)
    val lessons = Sched.lessonsOn(d, date)
    val texts = listOf("№238, №240 (а, б)" to 1, "§12, вопросы 1–4" to 0, "" to 0)
    val ends = mutableMapOf<String, Int>()
    val es = if (lessons.isNotEmpty()) lessons.distinctBy { it.subjectId }.take(3).mapIndexed { i, l ->
        val (t, ph) = texts[i]
        HwEntry(
            subjectId = l.subjectId, subjectName = d.subject(l.subjectId)?.name ?: "?", text = t, noHw = i == 2,
            photos = List(ph) { "пример_$i.jpeg" }, lessonDate = date.toString(), lessonNum = l.number,
            due = Sched.dueFor(d, d.dueMode, l.subjectId, date).toString(),
        ).also { e -> l.end?.let { ends[e.id] = it } }
    } else listOf("Математика", "Химия", "История").mapIndexed { i, n ->
        val (t, ph) = texts[i]
        HwEntry(subjectName = n, text = t, noHw = i == 2, photos = List(ph) { "пример_$i.jpeg" },
            lessonDate = date.toString(), lessonNum = i + 1, due = date.plusDays(1).toString())
            .also { e -> ends[e.id] = 9 * 60 + 15 + i * 55 }
    }
    return Triple(date, es, ends)
}

@Composable
fun StudioScreen(push: (com.ilya.dzshka.Route) -> Unit = {}) {
    val d by Store.data.collectAsState()
    var applyPreset by remember { mutableStateOf<Preset?>(null) }
    var showPreview by remember { mutableStateOf(true) }

    fun updM(id: String, f: (Moment) -> Moment) = Store.update { a -> a.copy(moments = a.moments.map { if (it.id == id) f(it) else it }) }

    Scaffold(topBar = { TopAppBar(title = { Text("Студия отправки") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 48.dp)) {
            item {
                ElevatedCard(
                    onClick = { push(com.ilya.dzshka.Route.Test) },
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🧪", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Прогнать неделю за 10 минут", fontWeight = FontWeight.SemiBold)
                            Hint("Проверь настройки вживую: звонки, уведомления и что уйдёт в канал")
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 12.dp)) {
                    OutlinedButton(onClick = { push(com.ilya.dzshka.Route.Templates) }) { Text("Токены и названия (Ч, М, П…)") }
                }
            }
            item { ReminderStudioSection(d) }
            item {
                Section("Готовые варианты") {
                    Hint("Нажми — и всё настроится. Потом можно поменять что угодно ниже.")
                    Presets.list.forEach { p ->
                        OutlinedCard(onClick = { applyPreset = p }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold)
                                Hint(p.desc)
                            }
                        }
                    }
                }
            }

            item {
                Section("Как будет выглядеть день в канале") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { Hint("Пример: ДЗ вводятся после своих уроков, одно с фото, одно «нет ДЗ».") }
                        TextButton(onClick = { showPreview = !showPreview }) { Text(if (showPreview) "Скрыть" else "Показать") }
                    }
                    if (showPreview) {
                        val sim = remember(d) {
                            val (date, es, ends) = sampleDay(d)
                            Studio.simulate(d, date, es, ends)
                        }
                        ChannelPreview(sim)
                    }
                }
            }

            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text("Моменты отправки", style = MaterialTheme.typography.titleMedium)
                    Hint("Момент — это КОГДА уходят сообщения. Внутри — ЧТО уходит и в каком порядке.")
                }
            }

            items(d.moments, key = { it.id }) { m -> MomentCard(d, m, ::updM) }

            item {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Store.update { it.copy(moments = it.moments + Moment(name = "Новый момент", trigger = Trigger.AT_TIME, time = "18:00", steps = listOf(Step.of(StepKind.ALL_TEXT)))) }
                    }) { Text("+ момент отправки") }
                }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Hint("Если есть момент «Сразу после ввода», ДЗ уходит сразу. Если нет — копится и уходит в ближайший момент по времени (или по кнопке на главной).")
                    Hint("Заголовок дня с галочкой «раз в день» не повторится, даже если стоит в нескольких моментах.")
                }
            }
        }
    }

    applyPreset?.let { p ->
        ConfirmDialog("«${p.name}»", "Заменить текущие моменты отправки этим вариантом?\n\n${p.desc}", "Заменить",
            onYes = { Store.update { it.copy(moments = p.build()) } }, onDismiss = { applyPreset = null })
    }
}

@Composable
private fun MomentCard(d: AppData, m: Moment, updM: (String, (Moment) -> Moment) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    val sctx = remember(d) { sampleDay(d) }

    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name.ifBlank { "Момент" }, fontWeight = FontWeight.SemiBold)
                    Hint(Studio.whenText(m) + " · " + m.steps.joinToString(" → ") { it.kind.short }.ifBlank { "нет сообщений" })
                }
                Switch(checked = m.enabled, onCheckedChange = { v -> updM(m.id) { it.copy(enabled = v) } })
                IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.Edit, "Изменить момент") }
            }
            if (expanded) {
                TextSetting("Название", m.name, { v -> updM(m.id) { it.copy(name = v) } })
                Dropdown("Когда", m.trigger, Trigger.entries, { it.label }, { v -> updM(m.id) { it.copy(trigger = v) } })
                when (m.trigger) {
                    Trigger.FIRST_LESSON, Trigger.AFTER_LAST ->
                        IntSetting("Сдвиг, мин", m.offsetMin, { n -> updM(m.id) { it.copy(offsetMin = n) } }, -180..600,
                            hint = if (m.trigger == Trigger.FIRST_LESSON) "-10 — за 10 минут до первого урока" else "10 — через 10 минут после звонка")
                    Trigger.AT_TIME -> {
                        TimeButton(m.time, { v -> updM(m.id) { it.copy(time = v) } }, label = "Время:")
                        SwitchRow("Только в учебные дни", m.onlySchoolDays, { v -> updM(m.id) { it.copy(onlySchoolDays = v) } })
                    }
                    else -> {}
                }

                Text("Сообщения по порядку", style = MaterialTheme.typography.labelLarge)
                m.steps.forEachIndexed { i, st ->
                    StepCard(d, st, i, m.steps.size, sctx,
                        onChange = { ns -> updM(m.id) { mm -> mm.copy(steps = mm.steps.map { if (it.id == st.id) ns else it }) } },
                        onMove = { dir ->
                            updM(m.id) { mm ->
                                val l = mm.steps.toMutableList()
                                val j = i + dir
                                if (j in l.indices) { val t = l[i]; l[i] = l[j]; l[j] = t }
                                mm.copy(steps = l)
                            }
                        },
                        onDelete = { updM(m.id) { mm -> mm.copy(steps = mm.steps.filterNot { it.id == st.id }) } })
                }
                Box {
                    OutlinedButton(onClick = { addOpen = true }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Добавить сообщение") }
                    DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                        StepKind.entries.forEach { k ->
                            DropdownMenuItem(text = { Text(k.short + " — " + k.label) }, onClick = {
                                updM(m.id) { it.copy(steps = it.steps + Step.of(k)) }
                                addOpen = false
                            })
                        }
                    }
                }
                Row {
                    TextButton(onClick = {
                        Store.update { a -> a.copy(moments = a.moments + m.copy(id = newId(), name = m.name + " (копия)", steps = m.steps.map { it.copy(id = newId()) })) }
                    }) { Text("Копировать") }
                    TextButton(onClick = { del = true }) { Text("Удалить момент", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (del) ConfirmDialog("Удалить «${m.name}»?", "Сообщения этого момента больше не будут уходить.", "Удалить",
        onYes = { Store.update { a -> a.copy(moments = a.moments.filterNot { it.id == m.id }) } }, onDismiss = { del = false })
}

@Composable
private fun StepCard(
    d: AppData, st: Step, index: Int, count: Int,
    sample: Triple<LocalDate, List<HwEntry>, Map<String, Int>>,
    onChange: (Step) -> Unit, onMove: (Int) -> Unit, onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}.", Modifier.width(24.dp), fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    Text(st.kind.label, style = MaterialTheme.typography.bodyMedium)
                    val preview = when (st.kind) {
                        StepKind.HW_PHOTOS, StepKind.ALL_PHOTOS -> st.caption
                        else -> st.template
                    }
                    if (preview.isNotBlank()) Hint(preview.replace("\n", " ⏎ ").take(60))
                }
                IconButton(onClick = { onMove(-1) }, enabled = index > 0, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ArrowUpward, "Выше") }
                IconButton(onClick = { onMove(1) }, enabled = index < count - 1, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ArrowDownward, "Ниже") }
                IconButton(onClick = { open = !open }, modifier = Modifier.size(36.dp)) { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.Tune, "Настроить") }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.Close, "Убрать") }
            }
            if (open) {
                val (date, es, _) = sample
                val e0 = es.first()
                val ec = Builder.ctx(d, e0, date)
                val dc = Builder.dayCtx(d, st.dateKind, date)
                when (st.kind) {
                    StepKind.DAY_HEADER, StepKind.TEXT -> {
                        StepTpl("Текст", st.template, d, dc) { onChange(st.copy(template = it)) }
                        Dropdown("Какая дата в токенах Ч, М, Д", st.dateKind, HeaderDate.entries, { it.label }, { onChange(st.copy(dateKind = it)) })
                        SwitchRow("Не больше одного раза в день", st.onceADay, { onChange(st.copy(onceADay = it)) },
                            desc = if (st.kind == StepKind.DAY_HEADER) "Общий для всех заголовков: если утром уже был — второй раз не придёт" else null)
                        SwitchRow("Только если есть ДЗ", st.onlyIfHw, { onChange(st.copy(onlyIfHw = it)) },
                            desc = "Выключи, чтобы уходило всегда (например, утром)")
                    }
                    StepKind.HW_TEXT -> {
                        StepTpl("Текст для каждого ДЗ", st.template, d, ec) { onChange(st.copy(template = it)) }
                        SwitchRow("Пропускать «нет ДЗ»", st.skipNoHw, { onChange(st.copy(skipNoHw = it)) })
                    }
                    StepKind.HW_WITH_PHOTO -> {
                        StepTpl("Текст (станет подписью к первому фото)", st.template, d, ec) { onChange(st.copy(template = it)) }
                        StepTpl("Подпись к остальным фото", st.caption, d, ec) { onChange(st.copy(caption = it)) }
                        Hint("Если фото нет — уйдёт обычным текстом. Подпись в Viber — до 768 символов, длиннее уйдёт отдельно.")
                        SwitchRow("Пропускать «нет ДЗ»", st.skipNoHw, { onChange(st.copy(skipNoHw = it)) })
                    }
                    StepKind.HW_PHOTOS, StepKind.ALL_PHOTOS -> {
                        StepTpl("Подпись к фото", st.caption, d, ec) { onChange(st.copy(caption = it)) }
                    }
                    StepKind.ALL_TEXT -> {
                        StepTpl("Заголовок списка", st.template, d, ec) { onChange(st.copy(template = it)) }
                        StepTpl("Строка одного ДЗ", st.line, d, ec) { onChange(st.copy(line = it)) }
                        TextSetting("Между строками", st.separator.replace("\n", "\\n"), { onChange(st.copy(separator = it.replace("\\n", "\n"))) }, hint = "\\n — перенос строки")
                        StepTpl("Подвал", st.footer, d, ec) { onChange(st.copy(footer = it)) }
                        SwitchRow("Разбить по датам сдачи", st.groupByDate, { onChange(st.copy(groupByDate = it)) }, desc = "Свой заголовок для каждой даты")
                        if (st.groupByDate) TextSetting("Между датами", st.groupGap.replace("\n", "\\n"), { onChange(st.copy(groupGap = it.replace("\\n", "\n"))) })
                        SwitchRow("Пропускать «нет ДЗ»", st.skipNoHw, { onChange(st.copy(skipNoHw = it)) })
                        Text("Пример:", style = MaterialTheme.typography.labelMedium)
                        PreviewBox(Studio.listText(d, st, if (st.skipNoHw) es.filterNot { it.noHw } else es, date))
                    }
                }
                Dropdown("Вид сообщения", st.kind, StepKind.entries, { it.label }, { k ->
                    val base = Step.of(k)
                    onChange(base.copy(id = st.id))
                })
            }
        }
    }
}

@Composable
private fun StepTpl(label: String, value: String, d: AppData, c: TplCtx, onChange: (String) -> Unit) {
    TextSetting(label, value, onChange, singleLine = false)
    if (value.isNotBlank()) Text("→ " + Builder.r(d, value, c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

/** Пузыри сообщений, как в канале Viber. */
@Composable
fun ChannelPreview(events: List<Studio.SimEvent>, showTimes: Boolean = true) {
    if (events.isEmpty()) {
        Hint("В этот день в канал ничего не уйдёт. Включи хотя бы один момент с сообщениями.")
        return
    }
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        var lastLabel = ""
        events.forEach { ev ->
            val label = "${ev.time} · ${ev.title}"
            if (showTimes && label != lastLabel) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                lastLabel = label
            }
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth(0.88f),
            ) {
                Column(Modifier.padding(10.dp)) {
                    when (val p = ev.post) {
                        is Post.Text -> Text(p.text)
                        is Post.Photo -> {
                            Box(
                                Modifier.fillMaxWidth().height(90.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) { Text("🖼 фото") }
                            if (p.caption.isNotBlank()) Text(p.caption, Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
