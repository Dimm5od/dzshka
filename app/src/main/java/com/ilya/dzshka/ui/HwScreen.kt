@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ilya.dzshka.data.*
import com.ilya.dzshka.net.GhSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/** Вкладка «ДЗ»: дни и уроки без звонков — что делать на завтра, что задали сегодня, в какие дни что задавали. Хранится 2 месяца. */
@Composable
fun HwScreen(initialSubject: String? = null, onBack: (() -> Unit)? = null) {
    val d by Store.data.collectAsState()
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var filter by remember { mutableStateOf(initialSubject?.takeIf { it.isNotBlank() }) }
    var syncMsg by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }

    val all = remember(d) { Archive.items(d) }
    val items = remember(all, filter) { if (filter == null) all else all.filter { it.rec.subjectId == filter } }
    val subjects = remember(d) {
        (d.subjects.map { it.id to it.name } + all.map { it.rec.subjectId to it.rec.subjectName })
            .filter { it.first.isNotBlank() }.distinctBy { it.first }
    }
    // по дням, когда задали (последние 2 месяца), новые сверху
    val byGiven = remember(items) {
        items.mapNotNull { i -> Sched.parseDate(i.rec.lessonDate)?.let { it to i } }
            .filter { it.first >= today.minusDays(Archive.KEEP_DAYS.toLong()) }
            .groupBy({ it.first }, { it.second }).toSortedMap(compareByDescending { it })
    }

    fun sync() {
        syncing = true; syncMsg = "Синхронизирую…"
        scope.launch {
            syncMsg = withContext(Dispatchers.IO) { runCatching { GhSync.sync() }.getOrElse { "✗ " + (it.message ?: "ошибка") } }
            syncing = false
        }
    }
    LaunchedEffect(Unit) { if (d.gh.enabled && System.currentTimeMillis() - d.gh.lastSync > 10 * 60_000) sync() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (filter != null) "ДЗ: ${subjects.firstOrNull { it.first == filter }?.second ?: ""}" else "ДЗ") },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = { if (d.gh.enabled) IconButton(onClick = { sync() }, enabled = !syncing) { Icon(Icons.Default.Sync, "Синхронизировать") } },
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (syncMsg.isNotBlank() || d.gh.lastError.isNotBlank()) item {
                val m = syncMsg.ifBlank { "✗ " + d.gh.lastError }
                Text(m, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = if (m.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Все") }) }
                    items(subjects, key = { it.first }) { (id, name) ->
                        val emoji = d.subject(id)?.emoji?.takeIf { it.isNotBlank() }
                        FilterChip(selected = filter == id, onClick = { filter = if (filter == id) null else id },
                            label = { Text(listOfNotNull(emoji, name).joinToString(" ")) })
                    }
                }
            }
            if (filter != null) item { Summary(d, items, filter, today) }
            else {
                val next = Sched.nextSchoolDay(d, today)
                item {
                    Section("⏭ Что делать на " + if (next == today.plusDays(1)) "завтра" else prettyDate(d, next)) {
                        DayPlan(d, next, all, byDue = true)
                    }
                }
                item {
                    Section("✏ Сегодня задали") {
                        if (Sched.lessonsOn(d, today).isEmpty() && all.none { it.rec.lessonDate == today.toString() }) Hint("Сегодня уроков нет")
                        else DayPlan(d, today, all, byDue = false)
                    }
                }
            }
            item {
                Text(if (byGiven.isEmpty()) "За 2 месяца ничего не записано" else "Задавали по дням",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            var lastMonth = -1
            byGiven.forEach { (day, list) ->
                if (day.monthValue != lastMonth) {
                    lastMonth = day.monthValue
                    item(key = "m$day") {
                        Text(d.monthsB.getOrElse(day.monthValue - 1) { "" }.replaceFirstChar { it.uppercase() },
                            Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                item(key = day.toString()) {
                    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium,
                        color = if (day == today) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val r = rel(day, today)
                            Text(prettyDate(d, day) + if (r.isNotBlank()) " · $r" else "", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            list.forEach { HwLine(d, it, showSubject = filter == null, showDue = true) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * День как в расписании, но без звонков: каждый урок и ДЗ по нему.
 * byDue = true — что задано НА этот день (что делать), false — что задали В этот день.
 */
@Composable
private fun DayPlan(d: AppData, day: LocalDate, all: List<Archive.Item>, byDue: Boolean) {
    val lessons = Sched.lessonsOn(d, day).distinctBy { it.subjectId }
    val list = all.filter { if (byDue) it.day == day else it.rec.lessonDate == day.toString() }
    val shown = mutableSetOf<String>()
    lessons.forEach { l ->
        val s = d.subject(l.subjectId)
        val mine = list.filter { it.rec.subjectId == l.subjectId }
        val name = listOfNotNull(s?.emoji?.takeIf { it.isNotBlank() }, s?.name ?: "?").joinToString(" ")
        if (mine.isEmpty()) Text("$name — " + if (byDue) "ничего не задано" else "не записано",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        else mine.forEach { shown += it.rec.id; HwLine(d, it, showSubject = true) }
    }
    // ДЗ по предметам, которых в этот день нет в расписании
    list.filter { it.rec.id !in shown }.forEach { HwLine(d, it, showSubject = true) }
    if (lessons.isEmpty() && list.isEmpty()) Hint("Уроков нет")
}

private fun rel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "сегодня"
    today.plusDays(1) -> "завтра"
    today.minusDays(1) -> "вчера"
    today.plusDays(2) -> "послезавтра"
    else -> ""
}

@Composable
private fun Summary(d: AppData, items: List<Archive.Item>, filter: String?, today: LocalDate) {
    fun on(day: LocalDate) = items.filter { it.day == day }
    val next = Sched.nextSchoolDay(d, today)
    Section(if (filter != null) "Что задали" else "Коротко") {
        if (filter != null) {
            SummaryLine("📌 На сегодня", on(today), d, showSubject = false)
            SummaryLine("↩ На вчера", on(today.minusDays(1)), d, showSubject = false)
            SummaryLine("⏭ На завтра", on(today.plusDays(1)), d, showSubject = false)
            if (on(today.plusDays(1)).isEmpty()) {
                val later = items.filter { (it.day ?: today) > today.plusDays(1) }.minByOrNull { it.day!! }
                if (later != null) SummaryLine("📅 Дальше — на ${prettyDate(d, later.day!!)}", listOf(later), d, showSubject = false)
            }
            val last = items.filter { (it.day ?: today) < today.minusDays(1) }.maxByOrNull { it.day!! }
            if (last != null && on(today).isEmpty() && on(today.minusDays(1)).isEmpty())
                SummaryLine("🕘 Последнее — на ${prettyDate(d, last.day!!)}", listOf(last), d, showSubject = false)
        } else {
            SummaryLine("📌 На сегодня", on(today), d, showSubject = true)
            val nextLabel = if (next == today.plusDays(1)) "⏭ На завтра" else "⏭ На ${prettyDate(d, next)}"
            SummaryLine(nextLabel, on(next), d, showSubject = true)
        }
    }
}

@Composable
private fun SummaryLine(title: String, list: List<Archive.Item>, d: AppData, showSubject: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (list.isEmpty()) Text("ничего не записано", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        list.forEach { HwLine(d, it, showSubject) }
    }
}

@Composable
private fun HwLine(d: AppData, it: Archive.Item, showSubject: Boolean, showMeta: Boolean = false, showDue: Boolean = false) {
    val ctx = LocalContext.current
    val r = it.rec
    val s = d.subject(r.subjectId)
    val name = listOfNotNull(s?.emoji?.takeIf { e -> e.isNotBlank() }, s?.name ?: r.subjectName).joinToString(" ")
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(
            (if (showSubject) "$name — " else "") + Archive.label(d, r),
            style = MaterialTheme.typography.bodyMedium,
            color = if (r.none) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
        )
        val meta = buildList {
            if (showMeta) Sched.parseDate(r.lessonDate)?.let { add("задали ${prettyDate(d, it)}") }
            if (showDue && !r.none) Sched.parseDate(r.due)?.let { add("на ${prettyDate(d, it)}") }
            if (r.author.isNotBlank()) add(r.author)
            if (r.source == "chat") add("из чата")
            if (it.pending) add("ещё не отправлено")
        }
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        if (r.photos.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            r.photos.forEachIndexed { i, u ->
                AssistChip(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    label = { Text("📷 ${i + 1}") })
            }
        }
    }
}

