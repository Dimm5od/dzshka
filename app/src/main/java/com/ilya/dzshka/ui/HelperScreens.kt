@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.Route
import com.ilya.dzshka.data.*
import com.ilya.dzshka.link.AckState
import com.ilya.dzshka.link.HelperLink
import com.ilya.dzshka.link.Link
import com.ilya.dzshka.work.LinkPollWorker
import com.ilya.dzshka.work.LinkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

/** Первый запуск помощника: имя + код. */
@Composable
fun HelperConnectScreen() {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    Scaffold { pad ->
        Box(Modifier.padding(pad)) {
            CodeForm(
                title = "👋 ДЗшка Помощник",
                explain = "Ты будешь присылать ДЗ главному (тому, кто ведёт канал). Напиши своё имя и код, который он тебе сказал. Расписание и звонки придут от него сами.",
                nameLabel = "Твоё имя",
                initialName = d.link.myName,
                initialServer = d.link.server,
                confirmTwice = false,
                hidden = true,
                button = "Подключиться",
                onLater = null,
            ) { code, name, server ->
                withContext(Dispatchers.IO) {
                    HelperLink.connect(code, name, server)
                    val r = runCatching {
                        HelperLink.hello()
                        // ждём ответ основного до ~15 секунд
                        repeat(6) {
                            Thread.sleep(2500)
                            Link.pollNow(ctx)
                            if (Store.d.link.cfgVer != 0L) return@runCatching true
                        }
                        false
                    }
                    withContext(Dispatchers.Main) {
                        LinkService.sync(ctx)
                        LinkPollWorker.schedule(ctx)
                    }
                    when {
                        r.isFailure -> { Store.update { it.copy(link = LinkCfg(deviceId = it.link.deviceId, myName = name, server = server)) }
                            "Нет связи: ${r.exceptionOrNull()?.message}. Проверь интернет." }
                        r.getOrNull() == true -> null.also { withContext(Dispatchers.Main) { Toast.makeText(ctx, "Подключено ✓", Toast.LENGTH_SHORT).show() } }
                        else -> null.also { withContext(Dispatchers.Main) {
                            Toast.makeText(ctx, "Запрос отправлен. Расписание придёт, когда главный откроет ДЗшку.", Toast.LENGTH_LONG).show() } }
                    }
                }
            }
        }
    }
}

@Composable
fun HelperStatusCard(d: AppData) {
    val l = d.link
    val (icon, title, text) = when (l.myStatus) {
        HelperStatus.ACTIVE -> Triple("✅", "Подключено к: ${l.mainName.ifBlank { "главному" }}", "Твои ДЗ уходят в канал «${d.viber.channelName.ifBlank { "канал" }}»" + if (l.myNeedsApproval) " после проверки" else "")
        HelperStatus.PENDING -> Triple("⏳", "Ждём, пока ${l.mainName.ifBlank { "главный" }} тебя одобрит", "ДЗ уже можно присылать — они уйдут после одобрения")
        HelperStatus.BLOCKED -> Triple("⛔", "Главный тебя отключил", "ДЗ не принимаются")
    }
    Section("$icon $title") {
        Text(text)
        if (l.myAllowed.isNotEmpty()) Hint("Предметы: " + l.myAllowed.mapNotNull { id -> d.subject(id)?.name }.joinToString(", "))
        Hint("Тебе можно: ДЗ текстом по сегодняшним урокам" + l.myPerms.joinToString("") { ", " + it.label.replace("«", "").replace("»", "").lowercase() })
        if (l.cfgVer == 0L) Hint("Расписание ещё не пришло. Оно придёт, когда главный будет в сети.")
        else Hint("Последняя связь: ${ago(l.lastContact)}")
    }
}

@Composable
fun HelperHomeScreen(push: (Route) -> Unit) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { Link.pollNow(ctx) } }
        while (true) { delay(30_000); now = LocalDateTime.now() }
    }
    val today = now.toLocalDate()
    var showDay by remember { mutableStateOf(today) }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ДЗшка Помощник") }, actions = {
                IconButton(onClick = { LinkPollWorker.now(ctx); Toast.makeText(ctx, "Проверяю…", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Refresh, "Обновить") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val l = Sched.currentOrLast(d, now)
                    push(Route.Entry(subjectId = l?.subjectId, date = today.toString(), num = l?.number ?: 0))
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("ДЗ") },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item { HelperStatusCard(d) }
            if (HelperLink.can(d, HelperPerm.CHANNEL_POST)) item {
                var open by remember { mutableStateOf(false) }
                var txt by remember { mutableStateOf("") }
                Section("💬 Написать в канал") {
                    if (!open) OutlinedButton(onClick = { open = true }) { Text("Написать сообщение") }
                    else {
                        OutlinedTextField(txt, { txt = it }, label = { Text("Текст") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                        Row {
                            Button(enabled = txt.isNotBlank(), onClick = { HelperLink.sendPost(txt.trim()); txt = ""; open = false
                                Toast.makeText(ctx, "Отправлено главному", Toast.LENGTH_SHORT).show() }) { Text("Отправить") }
                            TextButton(onClick = { open = false }) { Text("Отмена") }
                        }
                        Hint(if (d.link.myNeedsApproval) "Главный проверит и отправит." else "Уйдёт в канал от имени канала.")
                    }
                }
            }
            item {
                Section("Уроки") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showDay = showDay.minusDays(1) }) { Icon(Icons.Default.ChevronLeft, "Назад") }
                        Text((if (showDay == today) "Сегодня, " else "") + prettyDate(d, showDay), Modifier.weight(1f).clickable { showDay = today },
                            style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { showDay = showDay.plusDays(1) }) { Icon(Icons.Default.ChevronRight, "Вперёд") }
                    }
                    val lessons = Sched.lessonsOn(d, showDay)
                    if (lessons.isEmpty()) Text(if (Sched.isHoliday(d, showDay)) "Каникулы / выходной 🎉" else "Уроков нет")
                    lessons.forEach { l ->
                        val s = d.subject(l.subjectId)
                        val sent = d.isSent(l.subjectId, l.date.toString())
                        val noSend = s?.noSend == true
                        val skipped = d.isSkipped(l.subjectId, l.date.toString())
                        Row(Modifier.fillMaxWidth().clickable { push(Route.Entry(subjectId = l.subjectId, date = l.date.toString(), num = l.number)) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${l.number}", Modifier.width(24.dp), fontWeight = FontWeight.Bold)
                            Text(if (l.start != null && l.end != null) "${Time.fmt(l.start)}–${Time.fmt(l.end)}" else "", Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
                            Column(Modifier.weight(1f)) {
                                Text(listOfNotNull(s?.emoji?.takeIf { it.isNotBlank() }, s?.name ?: "?").joinToString(" "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val mine = d.queue.firstOrNull { it.subjectId == l.subjectId && it.lessonDate == l.date.toString() }
                                val hw = lessonHwText(d, l, mine, sent)
                                val cs = MaterialTheme.colorScheme
                                val (status, color) = when {
                                    sent -> "✓ отправлено" to cs.primary
                                    mine != null -> "отправляется" to cs.secondary
                                    noSend -> "🚫 не присылаем" to cs.outline
                                    skipped -> "не задали" to cs.outline
                                    else -> null to cs.outline
                                }
                                if (status != null) Text(status + (hw?.let { " — $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            LessonHwButton(l)
                        }
                    }
                }
            }
            val sending = d.queue
            if (sending.isNotEmpty()) item {
                Section("Отправляется (${sending.size})") {
                    sending.forEach { e ->
                        Text("${e.subjectName}: " + (if (e.noHw) "нет ДЗ" else e.text.ifBlank { "📷" }))
                        if (e.status == EntryStatus.FAILED) {
                            Text("✗ ${e.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { Store.updateEntry(e.id) { it.copy(status = EntryStatus.SENDING, error = "") }; com.ilya.dzshka.work.HelperSendWorker.enqueue(ctx, e.id) }) { Text("Повторить") }
                                TextButton(onClick = { Store.removeEntry(e.id, true) }) { Text("Удалить") }
                            }
                        } else Hint(e.error.ifBlank { "загружаю… (если нет интернета — отправлю, когда появится)" })
                    }
                }
            }
            if (d.outbox.isNotEmpty()) item {
                Text("Что я прислал", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            items(d.outbox.take(40), key = { it.clientId }) { o ->
                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(o.subjectName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            Text(fmt.format(Date(o.time)), style = MaterialTheme.typography.labelSmall)
                        }
                        Text(if (o.noHw) "нет ДЗ" else o.text.ifBlank { "📷 фото" } + if (o.photos > 0) " (фото: ${o.photos})" else "")
                        val st = o.state
                        Text(
                            when {
                                o.error.isNotBlank() -> "⚠ ${o.error}"
                                st == null -> "⏳ ${o.note.ifBlank { "отправляется" }}"
                                else -> (if (st == AckState.REJECTED) "✗ " else if (st == AckState.SENT) "" else "• ") + st.label + if (o.note.isNotBlank()) " — ${o.note}" else ""
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (st == AckState.REJECTED || o.error.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

/** Расписание у помощника — только посмотреть, меняет его главный. */
@Composable
fun HelperScheduleScreen() {
    val d by Store.data.collectAsState()
    var weekStart by remember { mutableStateOf(LocalDate.now().with(java.time.DayOfWeek.MONDAY)) }
    Scaffold(topBar = { TopAppBar(title = { Text("Расписание") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { weekStart = weekStart.minusWeeks(1) }) { Icon(Icons.Default.ChevronLeft, "Назад") }
                    Text("Неделя с ${prettyDate(d, weekStart)}" + if (d.weeks.size > 1) " · ${d.weeks.getOrNull(Sched.weekIndex(d, weekStart))?.name ?: ""}" else "",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { weekStart = weekStart.plusWeeks(1) }) { Icon(Icons.Default.ChevronRight, "Вперёд") }
                }
                Column(Modifier.padding(horizontal = 16.dp)) { Hint("Расписание присылает главный. Если оно поменялось — обновится само.") }
            }
            items((0..6).map { weekStart.plusDays(it.toLong()) }) { day ->
                val lessons = Sched.lessonsOn(d, day)
                if (lessons.isEmpty() && day.dayOfWeek.value >= 6) return@items
                Section(prettyDate(d, day)) {
                    if (lessons.isEmpty()) Text(if (Sched.isHoliday(d, day)) "Каникулы 🎉" else "Уроков нет")
                    lessons.forEach { l ->
                        Row {
                            Text("${l.number}.", Modifier.width(28.dp))
                            Text(if (l.start != null && l.end != null) "${Time.fmt(l.start)}–${Time.fmt(l.end)}" else "", Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall)
                            Text(d.subject(l.subjectId)?.name ?: "?")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HelperSettingsScreen(push: (Route) -> Unit) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    var leave by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 40.dp)) {
            item { HelperStatusCard(d) }
            item {
                Section("Я") {
                    TextSetting("Моё имя", d.link.myName, { v -> Store.update { it.copy(link = it.link.copy(myName = v)) } },
                        hint = "Главный увидит новое имя при следующей связи")
                    Hint("Ответы главного проверяются при каждом звонке, когда открываешь приложение и раз в 15 минут.")
                    TextButton(onClick = { leave = true }) { Text("Отключиться (ввести другой код)", color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                Section("Напоминания") {
                    Hint("Когда напоминать — решает главный. Здесь можно только звук и вибрацию у себя.")
                    val r = d.reminder
                    SwitchRow("Звук", r.sound, { v -> Store.update { it.copy(reminder = it.reminder.copy(sound = v)) } })
                    SwitchRow("Вибрация", r.vibrate, { v -> Store.update { it.copy(reminder = it.reminder.copy(vibrate = v)) } })
                }
            }
            item {
                Section("Браузер") {
                    Hint("Быстро найти что-то для ДЗ. Выдели текст на странице и нажми «В ДЗ» — он попадёт в новую запись.")
                    OutlinedButton(onClick = { push(Route.Browser) }) { Text("🌐 Открыть браузер") }
                }
            }
            item { PermissionsSection() }
            item {
                Section("Внешний вид") {
                    Dropdown("Тема", d.look.theme, ThemeMode.entries, { it.label }, { v -> Store.update { it.copy(look = it.look.copy(theme = v)) } })
                }
            }
            item { UpdateSection() }
        }
    }
    if (leave) ConfirmDialog("Отключиться?", "Главный перестанет получать твои ДЗ. Подключиться снова можно с кодом.", "Отключиться",
        onYes = { Thread { HelperLink.disconnect() }.start() }, onDismiss = { leave = false })
}
