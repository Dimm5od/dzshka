@file:OptIn(ExperimentalMaterial3Api::class)

package com.ilya.dzshka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.selection.SelectionContainer
import java.io.File
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.Route
import com.ilya.dzshka.data.*
import com.ilya.dzshka.work.Alarms
import com.ilya.dzshka.work.Sender
import kotlinx.coroutines.delay
import java.time.LocalDateTime

@Composable
fun HomeScreen(push: (Route) -> Unit) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = LocalDateTime.now() } }
    val today = now.toLocalDate()
    var showDay by remember { mutableStateOf(today) }
    var confirmDelete by remember { mutableStateOf<HwEntry?>(null) }
    var toTest by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Lesson?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ДЗшка") },
                actions = { IconButton(onClick = { push(Route.History) }) { Icon(Icons.Default.History, "История") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val l = Sched.currentOrLast(d, now)
                    push(Route.Entry(subjectId = l?.subjectId, date = today.toString(), num = l?.number ?: 0))
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("ДЗ") },
                modifier = Modifier.testTag("fab"),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            val pendingHelpers = d.helpers.filter { it.status == HelperStatus.PENDING }
            if (pendingHelpers.isNotEmpty()) item {
                Section("👥 Хотят стать помощниками") {
                    pendingHelpers.forEach { h ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${h.shownName} · ${h.model}", Modifier.weight(1f))
                            TextButton(onClick = { com.ilya.dzshka.link.MainLink.updateHelper(h.id) { it.copy(status = HelperStatus.ACTIVE) } }) { Text("Разрешить") }
                            TextButton(onClick = { com.ilya.dzshka.link.MainLink.updateHelper(h.id) { it.copy(status = HelperStatus.BLOCKED) } }) { Text("Нет") }
                        }
                    }
                }
            }
            val approvals = d.queue.filter { it.status == EntryStatus.APPROVAL }
            if (approvals.isNotEmpty()) item {
                Section("✋ Проверь ДЗ от помощников (${approvals.size})") {
                    approvals.forEach { e ->
                        Column {
                            Text("${e.author} · ${e.subjectName}", fontWeight = FontWeight.SemiBold)
                            Text(if (e.noHw) d.templates.noHw else e.text.ifBlank { "📷 фото" } + if (e.photos.isNotEmpty()) " (фото: ${e.photos.size})" else "")
                            Row {
                                TextButton(onClick = { Thread { com.ilya.dzshka.link.MainLink.approve(ctx, e.id) }.start() }) { Text("Одобрить") }
                                TextButton(onClick = { push(Route.Entry(editId = e.id)) }) { Text("Изменить") }
                                TextButton(onClick = { Thread { com.ilya.dzshka.link.MainLink.reject(ctx, e.id) }.start() }) { Text("Отклонить", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            if (d.pendingPosts.isNotEmpty()) item {
                Section("💬 Хотят написать в канал (${d.pendingPosts.size})") {
                    d.pendingPosts.forEach { pp ->
                        Column {
                            Text(pp.author, fontWeight = FontWeight.SemiBold)
                            Text(pp.text)
                            Row {
                                TextButton(onClick = { Thread { com.ilya.dzshka.link.MainLink.postNow(pp) }.start() }) { Text("Отправить") }
                                TextButton(onClick = { Thread { com.ilya.dzshka.link.MainLink.rejectPost(pp.id) }.start() }) { Text("Отклонить", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            if (d.viber.token.isBlank() || d.viber.fromId.isBlank()) item {
                Section("⚠ Viber не настроен") {
                    Text("Без токена канала ДЗ будет только копиться в очереди.")
                    Button(onClick = { push(Route.Wizard) }) { Text("Открыть мастер настройки") }
                }
            }

            item {
                Section("Уроки") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showDay = showDay.minusDays(1) }) { Icon(Icons.Default.ChevronLeft, "Назад") }
                        Text(
                            (if (showDay == today) "Сегодня, " else "") + prettyDate(d, showDay),
                            Modifier.weight(1f).clickable { showDay = today },
                            style = MaterialTheme.typography.titleSmall,
                        )
                        IconButton(onClick = { showDay = showDay.plusDays(1) }) { Icon(Icons.Default.ChevronRight, "Вперёд") }
                    }
                    val lessons = Sched.lessonsOn(d, showDay)
                    if (lessons.isEmpty()) {
                        Text(if (Sched.isHoliday(d, showDay)) "Каникулы / выходной 🎉" else "Уроков нет")
                        if (d.subjects.isEmpty()) Hint("Расписание пустое — заполни его во вкладке «Расписание» или вставь текстом.")
                    }
                    lessons.forEach { l ->
                        // нажатие на предмет — карточка урока (что задали, кнопка «Показать ДЗ»)
                        LessonRow(d, l, now) { info = l }
                    }
                }
            }

            if (d.queue.any { it.status != EntryStatus.APPROVAL }) {
                item {
                    Section("Очередь (${d.queue.count { it.status != EntryStatus.APPROVAL }})") {
                        val qm = Studio.queueMoment(d)
                        Button(onClick = {
                            if (toTest && d.testReady) {
                                Sender.sendTest(ctx, d.queue.filter { it.status != EntryStatus.APPROVAL }, qm.id)
                                Toast.makeText(ctx, "🧪 Отправляю в тест-канал", Toast.LENGTH_SHORT).show()
                            } else Sender.sendQueue(ctx)
                        }) { Text(if (toTest && d.testReady) "🧪 Отправить в тест-канал" else "Отправить сейчас") }
                        TestChannelCheck(toTest) { toTest = it }
                        Hint(
                            if (qm.trigger == Trigger.MANUAL || qm.trigger == Trigger.ON_ADD) "Уйдёт как в студии: ${qm.steps.joinToString(" → ") { it.kind.short }}"
                            else "Само уйдёт ${Studio.whenText(qm)} («${qm.name}»). Нажми на запись, чтобы изменить."
                        )
                    }
                }
                items(d.queue.filter { it.status != EntryStatus.APPROVAL }.sortedBy { it.created }, key = { it.id }) { e ->
                    QueueRow(d, e,
                        onOpen = { push(Route.Entry(editId = e.id)) },
                        onSend = {
                            if (toTest && d.testReady) Sender.sendTest(ctx, listOf(e), Studio.nowMoment(d).id)
                            else Sender.sendNow(ctx, listOf(e.id))
                        },
                        onDelete = { confirmDelete = e })
                }
            }

            // канал — под ДЗ
            item { ChannelHomeSection(d, push) }

            item {
                val plan = remember(d, now) { Alarms.lastPlanned }
                if (plan.isNotBlank()) Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { Hint("Дальше: $plan") }
            }

            // поиск — в самом низу, маленькой кнопкой
            item {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                    FilledTonalButton(onClick = { push(Route.Browser) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        modifier = Modifier.height(34.dp)) {
                        Icon(Icons.Default.Search, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text("Поиск", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    info?.let { l -> LessonInfoDialog(d, l, onShowHw = { info = null; push(Route.HwFor(l.subjectId)) }) { info = null } }

    confirmDelete?.let { e ->
        ConfirmDialog("Удалить запись?", "${e.subjectName}: ${e.text.ifBlank { if (e.noHw) "нет ДЗ" else "фото" }}", "Удалить",
            onYes = { Store.removeEntry(e.id, true) }, onDismiss = { confirmDelete = null })
    }
}

@Composable
private fun LessonRow(d: AppData, l: Lesson, now: LocalDateTime, onClick: () -> Unit) {
    val s = d.subject(l.subjectId)
    val inQueue = d.queue.firstOrNull { it.subjectId == l.subjectId && it.lessonDate == l.date.toString() }
    val sent = d.isSent(l.subjectId, l.date.toString())
    val m = now.hour * 60 + now.minute
    val isNow = l.date == now.toLocalDate() && l.start != null && l.end != null && m in l.start..l.end
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${l.number}", Modifier.width(24.dp), fontWeight = FontWeight.Bold)
        Text(
            if (l.start != null && l.end != null) "${Time.fmt(l.start)}–${Time.fmt(l.end)}" else "",
            Modifier.width(96.dp), style = MaterialTheme.typography.bodySmall,
        )
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(s?.emoji?.takeIf { it.isNotBlank() }, s?.name ?: "?").joinToString(" "),
                fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val skipped = d.isSkipped(l.subjectId, l.date.toString())
            val hw = lessonHwText(d, l, inQueue, sent)
            val cs = MaterialTheme.colorScheme
            val (status, color) = when {
                sent -> "✓ отправлено" to cs.primary
                inQueue?.status == EntryStatus.APPROVAL -> "ждёт одобрения" to cs.tertiary
                inQueue?.status == EntryStatus.FAILED -> "✗ ошибка" to cs.error
                inQueue != null -> "в очереди" to cs.secondary
                d.isFromChat(l.subjectId, l.date.toString()) -> "💬 из чата" to cs.primary
                s?.noSend == true -> "🚫 не присылаем" to cs.outline
                skipped -> "не задали" to cs.outline
                isNow -> "сейчас" to cs.primary
                else -> null to cs.outline
            }
            if (status != null) Text(
                status + (hw?.let { " — $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = color, maxLines = 3, overflow = TextOverflow.Ellipsis,
            )
        }
        LessonHwButton(l)
    }
}

/** Что записали по уроку (кто угодно) — показывается под предметом, пока лежит в очереди и после отправки. */
fun lessonHwText(d: AppData, l: Lesson, inQueue: HwEntry?, sent: Boolean): String? {
    if (inQueue == null && !sent) d.fromChat["${l.date}|${l.subjectId}"]?.let { return it }
    if (inQueue != null) return inQueue.text.ifBlank { if (inQueue.noHw) d.templates.noHw else if (inQueue.photos.isNotEmpty()) "📷 фото" else "" }
        .takeIf { it.isNotBlank() }
    if (!sent) return null
    return d.hwLog["${l.date}|${l.subjectId}"]?.takeIf { it.isNotBlank() }
}

/** Кнопка «ДЗ» у урока: в любой момент вызывает то же напоминание (сообщение или экранчик). */
@Composable
fun LessonHwButton(l: Lesson) {
    val ctx = LocalContext.current
    Spacer(Modifier.width(6.dp))
    FilledTonalButton(
        onClick = { com.ilya.dzshka.work.Notif.remind(ctx, l.date, l.index, l.number, l.subjectId) },
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.height(36.dp),
    ) { Text("ДЗ") }
}

@Composable
private fun QueueRow(d: AppData, e: HwEntry, onOpen: () -> Unit, onSend: () -> Unit, onDelete: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entryPreview(d, e), maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (e.photos.isNotEmpty()) Hint("📷 фото: ${e.photos.size}")
                if (e.author.isNotBlank()) Hint("👤 от ${e.author}")
                when (e.status) {
                    EntryStatus.SENDING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Hint(e.error.ifBlank { "отправляю… (ждёт интернет, если его нет)" })
                    }
                    EntryStatus.FAILED -> Text("✗ ${e.error}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    else -> {}
                }
            }
            Column {
                IconButton(onClick = onSend, enabled = e.status != EntryStatus.SENDING) { Icon(Icons.AutoMirrored.Filled.Send, "Отправить") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить") }
            }
        }
    }
}

/** Как запись будет выглядеть при отправке «сейчас» (без заголовков дня). */
fun entryPreview(d: AppData, e: HwEntry): String {
    val m = Studio.nowMoment(d)
    val steps = m.steps.filter { it.kind.usesEntries }
    return Studio.plan(d, steps, listOf(e), java.time.LocalDate.now(), emptyMap())
        .joinToString("\n") { p -> when (p) { is Post.Text -> p.text; is Post.Photo -> "🖼 " + p.caption } }
        .ifBlank { "${e.subjectName}: ${e.text}" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowButtons(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}


/** Карточка урока: что задали и всё вокруг. Только смотреть — записывать ДЗ через кнопку «ДЗ». */
@Composable
fun LessonInfoDialog(d: AppData, l: Lesson, onShowHw: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val s = d.subject(l.subjectId)
    val key = "${l.date}|${l.subjectId}"
    val q = d.queue.firstOrNull { it.subjectId == l.subjectId && it.lessonDate == l.date.toString() }
    val sent = d.isSent(l.subjectId, l.date.toString())
    val inf = d.hwInfo[key]
    val chat = d.fromChat[key]
    val text = q?.let { if (it.noHw) d.templates.noHw else it.text } ?: inf?.let { if (it.noHw) d.templates.noHw else it.text } ?: chat ?: d.hwLog[key] ?: ""
    val due = Sched.parseDate(q?.due ?: inf?.due ?: "")
    val author = q?.author ?: inf?.author
    val time = if (l.start != null && l.end != null) " · ${Time.fmt(l.start)}–${Time.fmt(l.end)}" else ""
    val next = remember(d, l) { Sched.nextLessonDate(d, l.subjectId, l.date)?.let { nd -> nd to Sched.lessonsOn(d, nd).firstOrNull { it.subjectId == l.subjectId } } }
    val prev = remember(d, l) {
        d.hwLog.entries.mapNotNull { (k, v) ->
            val (ds, sid) = k.split("|").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            val dt = Sched.parseDate(ds)
            if (sid == l.subjectId && dt != null && dt < l.date) dt to v else null
        }.maxByOrNull { it.first }
    }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(listOfNotNull(s?.emoji?.takeIf { it.isNotBlank() }, s?.name ?: "?").joinToString(" ")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("${l.number} урок · ${prettyDate(d, l.date)}$time", style = MaterialTheme.typography.bodySmall, color = cs.outline)
                val recorded = q != null || sent || chat != null
                // что было задано НА этот день по предмету — ответ на «а что задали?»
                val dueToday = remember(d, l) { Archive.items(d).filter { it.rec.subjectId == l.subjectId && it.day == l.date && it.rec.lessonDate != l.date.toString() } }
                if (dueToday.isNotEmpty()) {
                    Text("📌 На этот урок было задано:", style = MaterialTheme.typography.labelLarge, color = cs.primary)
                    dueToday.forEach { Text("• " + Archive.label(d, it.rec), style = MaterialTheme.typography.bodyMedium) }
                }
                Text(if (recorded) "Задали сегодня:" else "Сегодня по уроку ещё ничего не записано — кнопка «ДЗ».",
                    style = MaterialTheme.typography.labelLarge, color = if (recorded) cs.primary else cs.outline)
                if (recorded) Surface(color = cs.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(text.ifBlank { "📷 только фото" }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
                }
                val photos = q?.photos.orEmpty().filter { File(Store.photosDir(), it).exists() }
                if (photos.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(photos) { Thumb(File(Store.photosDir(), it), 80) }
                } else if ((inf?.photos ?: 0) > 0) Text("📷 фото: ${inf!!.photos}")
                if (recorded && due != null) Text("📅 На ${prettyDate(d, due)}")
                if (!recorded) {}
                else if (q == null && inf == null && chat != null) Text("💬 Из чата" + d.chatHw.chat.takeIf { it.isNotBlank() }?.let { " «$it»" }.orEmpty() + " — напоминания по уроку не было")
                else Text("✍ Записал: " + (author?.takeIf { it.isNotBlank() } ?: "я"))
                when {
                    sent -> Text("✓ Отправлено в канал" + (inf?.sentAt?.let { " · " + java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).let { z -> "%02d:%02d, %d.%02d".format(z.hour, z.minute, z.dayOfMonth, z.monthValue) } } ?: ""), color = cs.primary)
                    q?.status == EntryStatus.FAILED -> Text("✗ Ошибка: ${q.error}", color = cs.error)
                    q?.status == EntryStatus.APPROVAL -> Text("Ждёт твоего одобрения", color = cs.tertiary)
                    q?.status == EntryStatus.SENDING -> Text("Отправляется…", color = cs.secondary)
                    q != null -> Text("В очереди — уйдёт ${Studio.whenText(Studio.queueMoment(d))}", color = cs.secondary)
                }
                HorizontalDivider()
                next?.let { (nd, nl) ->
                    Text("⏭ Следующий урок: ${prettyDate(d, nd)}" + (nl?.let { " · ${it.number} урок" + (it.start?.let { st -> " в ${Time.fmt(st)}" } ?: "") } ?: ""))
                }
                prev?.let { (pd, pv) -> Text("↩ Прошлое ДЗ (${prettyDate(d, pd)}): $pv", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = { if (onShowHw != null) TextButton(onClick = onShowHw) { Text("📅 Показать ДЗ") } },
    )
}

