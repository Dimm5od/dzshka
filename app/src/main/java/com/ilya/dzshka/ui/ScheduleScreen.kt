@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.Route
import com.ilya.dzshka.SubScreen
import com.ilya.dzshka.data.*

private const val EMPTY = ""
private const val NEW = "__new__"

private fun AppData.editDay(week: Int, day: Int, f: (DaySchedule) -> DaySchedule): AppData =
    copy(weeks = weeks.mapIndexed { wi, w ->
        if (wi != week) w else w.copy(days = w.days.mapIndexed { di, ds -> if (di == day) f(ds) else ds })
    })

@Composable
fun ScheduleScreen(push: (Route) -> Unit) {
    val d by Store.data.collectAsState()
    var week by remember { mutableIntStateOf(0) }
    if (week >= d.weeks.size) week = 0
    var newSubjFor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var copyFrom by remember { mutableStateOf<Int?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Расписание") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { NowCard(d) }
            item { TodayBells(d) }
            item {
                Section("Настроить") {
                    FlowRowButtons {
                        OutlinedButton(onClick = { push(Route.Subjects) }) { Text("Предметы (${d.subjects.size})") }
                        OutlinedButton(onClick = { push(Route.Bells) }) { Text("Звонки") }
                        OutlinedButton(onClick = { push(Route.Import) }) { Text("Вставить текстом") }
                        OutlinedButton(onClick = { push(Route.Holidays) }) { Text("Каникулы (${d.holidays.size})") }
                    }
                }
            }
            item {
                Section("Недели") {
                    FlowRowButtons {
                        d.weeks.forEachIndexed { i, w -> FilterChip(selected = week == i, onClick = { week = i }, label = { Text(w.name) }) }
                        AssistChip(onClick = {
                            Store.update { it.copy(weeks = it.weeks + it.weeks[week].copy(name = "Неделя ${it.weeks.size + 1}"), weekAnchor = it.weekAnchor.ifBlank { java.time.LocalDate.now().toString() }) }
                        }, label = { Text("+ неделя") })
                    }
                    if (d.weeks.size > 1) {
                        TextSetting("Название недели", d.weeks[week].name, { v -> Store.update { it.copy(weeks = it.weeks.mapIndexed { i, w -> if (i == week) w.copy(name = v) else w }) } })
                        DateButton(d.weekAnchor, { v -> Store.update { it.copy(weekAnchor = v) } }, label = "Любой день недели «${d.weeks[0].name}»")
                        Hint("Недели чередуются по кругу. Укажи любой день, который точно относится к «${d.weeks[0].name}».")
                        TextButton(onClick = {
                            Store.update { it.copy(weeks = it.weeks.filterIndexed { i, _ -> i != week }) }
                            week = 0
                        }) { Text("Удалить эту неделю", color = MaterialTheme.colorScheme.error) }
                    } else Hint("Если расписание разное по неделям (числитель/знаменатель) — добавь вторую неделю.")
                }
            }
            itemsIndexed(d.weeks[week].days) { di, day ->
                DayCard(d, week, di, day,
                    onNewSubject = { slot -> newSubjFor = di to slot },
                    onCopy = { copyFrom = di })
            }
        }
    }

    newSubjFor?.let { (di, slot) ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newSubjFor = null },
            title = { Text("Новый предмет") },
            text = { TextSetting("Название", name, { name = it }) },
            confirmButton = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        val s = Subject(name = name.trim())
                        Store.update { a ->
                            a.copy(subjects = a.subjects + s).editDay(week, di) { ds ->
                                ds.copy(lessons = ds.lessons.mapIndexed { i, x -> if (i == slot) s.id else x })
                            }
                        }
                    }
                    newSubjFor = null
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { newSubjFor = null }) { Text("Отмена") } },
        )
    }

    copyFrom?.let { src ->
        val targets = remember { mutableStateListOf<Int>() }
        AlertDialog(
            onDismissRequest = { copyFrom = null },
            title = { Text("Копировать «${d.weekdays.getOrElse(src) { "" }}» в…") },
            text = {
                Column {
                    (0..6).filter { it != src }.forEach { i ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = i in targets, onCheckedChange = { c -> if (c) targets += i else targets -= i })
                            Text(d.weekdays.getOrElse(i) { "$i" })
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val from = d.weeks[week].days[src]
                    Store.update { a -> targets.fold(a) { acc, t -> acc.editDay(week, t) { from } } }
                    copyFrom = null
                }) { Text("Копировать") }
            },
            dismissButton = { TextButton(onClick = { copyFrom = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun DayCard(d: AppData, week: Int, di: Int, day: DaySchedule, onNewSubject: (Int) -> Unit, onCopy: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val bell = d.bell(day.bellId)
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(d.weekdays.getOrElse(di) { "" }.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                    Hint(
                        if (!day.enabled) "выходной"
                        else day.lessons.filter { it.isNotBlank() }.joinToString(", ") { d.subject(it)?.let { s -> s.short.ifBlank { s.name } } ?: "?" }
                            .ifBlank { "уроков нет" }
                    )
                }
                Switch(checked = day.enabled, onCheckedChange = { v -> Store.update { it.editDay(week, di) { ds -> ds.copy(enabled = v) } } })
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                }
            }
            if (expanded) {
                if (d.bells.size > 1) Dropdown("Звонки", bell?.id ?: "", d.bells.map { it.id }, { id -> d.bell(id)?.name ?: "" },
                    { v -> Store.update { it.editDay(week, di) { ds -> ds.copy(bellId = v) } } })
                val options = listOf(EMPTY) + d.subjects.map { it.id } + NEW
                day.lessons.forEachIndexed { i, sid ->
                    val slot = bell?.slots?.getOrNull(i)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Column(Modifier.width(64.dp)) {
                            Text("${i + d.firstLessonNum} урок", style = MaterialTheme.typography.labelMedium)
                            if (slot != null) Hint(slot.start)
                        }
                        Dropdown("", sid, options, { id ->
                            when (id) {
                                EMPTY -> "— окно —"
                                NEW -> "+ новый предмет…"
                                else -> d.subject(id)?.name ?: "?"
                            }
                        }, { v ->
                            if (v == NEW) onNewSubject(i)
                            else Store.update { it.editDay(week, di) { ds -> ds.copy(lessons = ds.lessons.mapIndexed { k, x -> if (k == i) v else x }) } }
                        }, Modifier.weight(1f))
                        IconButton(onClick = {
                            if (i > 0) Store.update {
                                it.editDay(week, di) { ds -> ds.copy(lessons = ds.lessons.toMutableList().apply { add(i - 1, removeAt(i)) }) }
                            }
                        }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Выше") }
                        IconButton(onClick = {
                            Store.update { it.editDay(week, di) { ds -> ds.copy(lessons = ds.lessons.filterIndexed { k, _ -> k != i }) } }
                        }) { Icon(Icons.Default.Close, "Убрать") }
                    }
                }
                FlowRowButtons {
                    TextButton(onClick = {
                        Store.update { it.editDay(week, di) { ds -> ds.copy(enabled = true, lessons = ds.lessons + (d.subjects.firstOrNull()?.id ?: EMPTY)) } }
                    }) { Text("+ урок") }
                    TextButton(onClick = onCopy) { Text("Копировать в другие дни") }
                    TextButton(onClick = { Store.update { it.editDay(week, di) { ds -> ds.copy(lessons = emptyList()) } } }) { Text("Очистить") }
                }
                if (bell != null && day.lessons.size > bell.slots.size)
                    Hint("⚠ В «${bell.name}» только ${bell.slots.size} звонков — для лишних уроков не будет напоминаний.")
            }
        }
    }
}

// ---------------- Предметы ----------------

@Composable
fun SubjectsScreen(onBack: () -> Unit) {
    val d by Store.data.collectAsState()
    var del by remember { mutableStateOf<Subject?>(null) }
    SubScreen("Предметы", onBack, actions = {
        IconButton(onClick = { Store.update { it.copy(subjects = it.subjects.sortedBy { s -> s.name.lowercase() }) } }) {
            Icon(Icons.Default.SortByAlpha, "По алфавиту")
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Column(Modifier.padding(horizontal = 16.dp)) { Hint("Кратко — для сводки и подсказок (токен ПК). Эмодзи — токен Э.") } }
            itemsIndexed(d.subjects, key = { _, s -> s.id }) { _, s ->
                fun upd(f: (Subject) -> Subject) = Store.update { a -> a.copy(subjects = a.subjects.map { if (it.id == s.id) f(it) else it }) }
                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextSetting("Название", s.name, { v -> upd { it.copy(name = v) } })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextSetting("Кратко", s.short, { v -> upd { it.copy(short = v) } }, Modifier.weight(1f))
                            TextSetting("Эмодзи", s.emoji, { v -> upd { it.copy(emoji = v) } }, Modifier.weight(1f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SwitchRow("Напоминать", s.remind, { v -> upd { it.copy(remind = v) } }) }
                            IconButton(onClick = { del = s }) { Icon(Icons.Default.Delete, "Удалить") }
                        }
                        SwitchRow("🚫 Не присылать", s.noSend, { v -> upd { it.copy(noSend = v) } },
                            desc = "ДЗ по этому предмету не бывает (физра и т.п.): ни напоминаний, ни отправки")
                    }
                }
            }
            item {
                Row(Modifier.padding(12.dp)) {
                    Button(onClick = { Store.update { it.copy(subjects = it.subjects + Subject(name = "Новый предмет")) } }) { Text("+ предмет") }
                }
            }
        }
    }
    del?.let { s ->
        ConfirmDialog("Удалить «${s.name}»?", "Он пропадёт и из расписания (станет окном).", "Удалить", onYes = {
            Store.update { a ->
                a.copy(
                    subjects = a.subjects.filterNot { it.id == s.id },
                    weeks = a.weeks.map { w -> w.copy(days = w.days.map { ds -> ds.copy(lessons = ds.lessons.map { if (it == s.id) "" else it }) }) },
                )
            }
        }, onDismiss = { del = null })
    }
}

// ---------------- Звонки ----------------

@Composable
fun BellsScreen(onBack: () -> Unit) {
    val d by Store.data.collectAsState()
    SubScreen("Звонки", onBack) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Hint("Можно сделать несколько вариантов (например, сокращённые в пятницу) и выбрать их в дне расписания.")
                }
            }
            itemsIndexed(d.bells, key = { _, b -> b.id }) { _, b -> BellCard(d, b) }
            item {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Store.update { it.copy(bells = it.bells + Defaults.bells().copy(id = newId(), name = "Звонки ${it.bells.size + 1}")) } }) {
                        Text("+ вариант звонков")
                    }
                }
            }
        }
    }
}

@Composable
private fun BellCard(d: AppData, b: BellProfile) {
    fun upd(f: (BellProfile) -> BellProfile) = Store.update { a -> a.copy(bells = a.bells.map { if (it.id == b.id) f(it) else it }) }
    var gen by remember { mutableStateOf(false) }
    Section(b.name.ifBlank { "Звонки" }) {
        TextSetting("Название", b.name, { v -> upd { it.copy(name = v) } })
        b.slots.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${i + d.firstLessonNum}.", Modifier.width(28.dp))
                TimeButton(s.start, { v -> upd { it.copy(slots = it.slots.mapIndexed { k, x -> if (k == i) x.copy(start = v) else x }) } })
                Text("—")
                TimeButton(s.end, { v -> upd { it.copy(slots = it.slots.mapIndexed { k, x -> if (k == i) x.copy(end = v) else x }) } })
                IconButton(onClick = { upd { it.copy(slots = it.slots.filterIndexed { k, _ -> k != i }) } }) { Icon(Icons.Default.Close, "Убрать") }
            }
        }
        FlowRowButtons {
            TextButton(onClick = {
                upd {
                    val last = it.slots.lastOrNull()
                    val st = (last?.end?.let(Time::parse) ?: (8 * 60 + 20)) + 10
                    val len = last?.let { l -> (Time.parse(l.end) ?: 0) - (Time.parse(l.start) ?: 0) }?.takeIf { x -> x > 0 } ?: 45
                    it.copy(slots = it.slots + Slot(Time.fmt(st), Time.fmt(st + len)))
                }
            }) { Text("+ урок") }
            TextButton(onClick = { gen = !gen }) { Text("Сгенерировать") }
            if (d.bells.size > 1) TextButton(onClick = {
                Store.update { a ->
                    val rest = a.bells.filterNot { it.id == b.id }
                    a.copy(bells = rest, weeks = a.weeks.map { w -> w.copy(days = w.days.map { ds -> if (ds.bellId == b.id) ds.copy(bellId = rest.first().id) else ds }) })
                }
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
        }
        if (gen) {
            var start by remember { mutableStateOf(b.slots.firstOrNull()?.start ?: "08:30") }
            var len by remember { mutableIntStateOf(45) }
            var breaks by remember { mutableStateOf("10, 20, 10, 10, 10, 10") }
            var count by remember { mutableIntStateOf(maxOf(b.slots.size, 6)) }
            TimeButton(start, { start = it }, label = "Первый урок в")
            IntSetting("Длина урока, мин", len, { len = it }, 5..180)
            TextSetting("Перемены по порядку, мин", breaks, { breaks = it }, hint = "через запятую; последняя повторяется")
            IntSetting("Сколько уроков", count, { count = it }, 1..15)
            Button(onClick = {
                val br = breaks.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }
                upd { it.copy(slots = Defaults.generate(start, len, br, count)) }
                gen = false
            }) { Text("Заменить звонки") }
        }
    }
}

// ---------------- Импорт ----------------

@Composable
fun ImportScreen(onBack: () -> Unit) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var week by remember { mutableIntStateOf(0) }
    SubScreen("Вставить расписание", onBack) { pad ->
        Column(Modifier.padding(pad).padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Hint("Скопируй расписание откуда угодно (из чата класса, с фото через Google Lens) и вставь сюда. Понимаю такие варианты:")
            PreviewBox(
                "Пн: Математика, Русский язык, Физика\n\n" +
                    "Вторник\n1. Физика\n2. Алгебра\n3. —\n4. История\n\n" +
                    "Ср\n08:30-09:15 Химия\n09:25-10:10 Биология"
            )
            Hint("«—» или «окно» — пустой урок. Если указано время — сам сделаю звонки. Новые предметы создам сам. Дни, которых нет в тексте, не трогаю.")
            if (d.weeks.size > 1) Dropdown("В какую неделю", week, d.weeks.indices.toList(), { d.weeks[it].name }, { week = it })
            OutlinedTextField(text, { text = it }, label = { Text("Расписание") }, minLines = 10, modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val r = Sched.importText(Store.d, text, week)
                Store.update { r.data }
                Toast.makeText(ctx, r.report, Toast.LENGTH_LONG).show()
                onBack()
            }, enabled = text.isNotBlank()) { Text("Импортировать") }
        }
    }
}

// ---------------- Каникулы ----------------

@Composable
fun HolidaysScreen(onBack: () -> Unit) {
    val d by Store.data.collectAsState()
    SubScreen("Каникулы и выходные", onBack) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Column(Modifier.padding(horizontal = 16.dp)) { Hint("В эти дни нет напоминаний, и ДЗ «к следующему уроку» перескакивает через них.") } }
            itemsIndexed(d.holidays, key = { _, h -> h.id }) { _, h ->
                fun upd(f: (Holiday) -> Holiday) = Store.update { a -> a.copy(holidays = a.holidays.map { if (it.id == h.id) f(it) else it }) }
                Section(h.name.ifBlank { "Каникулы" }) {
                    TextSetting("Название", h.name, { v -> upd { it.copy(name = v) } })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DateButton(h.from, { v -> upd { it.copy(from = v, to = if (it.to.isBlank() || it.to < v) v else it.to) } }, label = "с")
                        DateButton(h.to, { v -> upd { it.copy(to = v) } }, label = "по")
                    }
                    TextButton(onClick = { Store.update { a -> a.copy(holidays = a.holidays.filterNot { it.id == h.id }) } }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            item {
                Row(Modifier.padding(12.dp)) {
                    val today = java.time.LocalDate.now().toString()
                    Button(onClick = { Store.update { it.copy(holidays = it.holidays + Holiday(from = today, to = today)) } }) { Text("+ каникулы / выходной") }
                }
            }
        }
    }
}

// ---------------- Сейчас: урок или перемена ----------------

private fun dur(min: Int): String = if (min >= 60) "${min / 60} ч ${min % 60} мин" else "$min мин"

/** Что сейчас: какой урок или перемена, сколько осталось, что дальше. Обновляется каждые 15 секунд. */
@Composable
fun NowCard(d: AppData) {
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(15_000); now = java.time.LocalDateTime.now() } }
    val today = now.toLocalDate()
    val m = now.hour * 60 + now.minute
    val sec = now.second
    val lessons = remember(d, today) { Sched.lessonsOn(d, today).filter { it.start != null && it.end != null } }
    fun name(l: Lesson) = d.subject(l.subjectId)?.let { listOfNotNull(it.emoji.takeIf { e -> e.isNotBlank() }, it.name).joinToString(" ") } ?: "?"
    val cs = MaterialTheme.colorScheme

    var big = ""; var small = ""; var next = ""; var prog: Float? = null
    val cur = lessons.firstOrNull { m >= it.start!! && m < it.end!! }
    val nextL = lessons.firstOrNull { it.start!! > m }
    when {
        Sched.isHoliday(d, today) -> { big = "Каникулы 🎉"; small = "Уроков нет" }
        lessons.isEmpty() -> { big = "Сегодня уроков нет"; small = "Выходной" }
        cur != null -> {
            big = "Идёт ${cur.number} урок"
            small = "${name(cur)} · ${Time.fmt(cur.start!!)}–${Time.fmt(cur.end!!)} · осталось ${dur(cur.end!! - m)}"
            prog = ((m - cur.start!!) * 60 + sec) / ((cur.end!! - cur.start!!) * 60f)
            if (nextL != null) next = "Потом перемена ${dur(nextL.start!! - cur.end!!)}, дальше ${nextL.number} урок — ${name(nextL)}"
            else next = "Это последний урок 🏁"
        }
        nextL != null && lessons.first().start!! > m -> {
            big = "Уроки начнутся в ${Time.fmt(nextL.start!!)}"
            small = "Через ${dur(nextL.start!! - m)} · ${nextL.number} урок — ${name(nextL)}"
        }
        nextL != null -> {
            val prev = lessons.last { it.end!! <= m }
            big = "Перемена · до звонка ${dur(nextL.start!! - m)}"
            small = "Дальше ${nextL.number} урок — ${name(nextL)} в ${Time.fmt(nextL.start!!)}"
            prog = ((m - prev.end!!) * 60 + sec) / ((nextL.start!! - prev.end!!) * 60f)
            next = "Перемена ${dur(nextL.start!! - prev.end!!)}"
        }
        else -> {
            big = "Уроки закончились ✅"
            val nd = Sched.nextSchoolDay(d, today)
            val first = Sched.lessonsOn(d, nd).firstOrNull { it.start != null }
            small = if (first != null) "Следующий день: ${prettyDate(d, nd)}, ${first.number} урок в ${Time.fmt(first.start!!)} — ${name(first)}" else ""
        }
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = cs.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Time.fmt(m), style = MaterialTheme.typography.labelLarge, color = cs.onPrimaryContainer.copy(alpha = 0.7f))
            Text(big, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer)
            if (small.isNotBlank()) Text(small, color = cs.onPrimaryContainer)
            prog?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
            if (next.isNotBlank()) Text(next, style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer.copy(alpha = 0.8f))
        }
    }
}

/** Сегодня по звонкам: уроки со временем и переменами между ними. */
@Composable
private fun TodayBells(d: AppData) {
    val today = java.time.LocalDate.now()
    val lessons = remember(d, today) { Sched.lessonsOn(d, today) }
    if (lessons.isEmpty()) return
    val nowM = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    Section("Сегодня, ${prettyDate(d, today)}") {
        lessons.forEachIndexed { i, l ->
            val s = d.subject(l.subjectId)
            val past = l.end != null && l.end <= nowM
            val cur = l.start != null && l.end != null && nowM in l.start until l.end
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${l.number}", Modifier.width(24.dp), fontWeight = FontWeight.Bold,
                    color = if (cur) MaterialTheme.colorScheme.primary else if (past) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                Text(if (l.start != null && l.end != null) "${Time.fmt(l.start)}–${Time.fmt(l.end)}" else "—", Modifier.width(100.dp),
                    style = MaterialTheme.typography.bodySmall, color = if (past) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                Text(listOfNotNull(s?.emoji?.takeIf { it.isNotBlank() }, s?.name ?: "?").joinToString(" "), Modifier.weight(1f),
                    fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                    color = if (past) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                if (cur) Text("сейчас", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            val n = lessons.getOrNull(i + 1)
            if (n?.start != null && l.end != null) Text("      перемена ${n.start - l.end} мин", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
