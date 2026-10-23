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

/** Вкладка «ДЗ»: календарь по месяцам, под днями — что задано на этот день. Фильтр по предмету. */
@Composable
fun HwScreen(initialSubject: String? = null, onBack: (() -> Unit)? = null) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var filter by remember { mutableStateOf(initialSubject?.takeIf { it.isNotBlank() }) }
    var selected by remember { mutableStateOf(today) }
    var syncMsg by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }

    val all = remember(d) { Archive.items(d) }
    val items = remember(all, filter) { if (filter == null) all else all.filter { it.rec.subjectId == filter } }
    val byDay = remember(items) { items.filter { it.day != null }.groupBy { it.day!! } }
    // предметы для фильтра: из расписания + те, что встречаются в архиве
    val subjects = remember(d) {
        (d.subjects.map { it.id to it.name } + all.map { it.rec.subjectId to it.rec.subjectName })
            .filter { it.first.isNotBlank() }.distinctBy { it.first }
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
            title = { Text(if (filter != null) "ДЗ: ${subjects.firstOrNull { it.first == filter }?.second ?: ""}" else "Всё ДЗ") },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            actions = { if (d.gh.enabled) IconButton(onClick = { sync() }, enabled = !syncing) { Icon(Icons.Default.Sync, "Синхронизировать") } },
        )
    }) { pad ->
        val list = rememberLazyListState()
        LazyColumn(Modifier.padding(pad), state = list, contentPadding = PaddingValues(bottom = 40.dp)) {
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
            item { Summary(d, items, filter, today) }
            item {
                Section("") {
                    MonthHeader(d, month, onPrev = { month = month.minusMonths(1) }, onNext = { month = month.plusMonths(1) },
                        onToday = { month = YearMonth.from(today); selected = today })
                    MonthGrid(d, month, byDay, selected, today) { selected = it }
                    DayBlock(d, selected, byDay[selected].orEmpty(), today, ctx, big = true)
                }
            }
            val days = byDay.keys.filter { YearMonth.from(it) == month }.sorted()
            item {
                Text(if (days.isEmpty()) "В этом месяце ДЗ нет" else "Весь ${d.monthsB.getOrElse(month.monthValue - 1) { "" }}",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
            items(days, key = { it.toString() }) { day ->
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { selected = day },
                    shape = MaterialTheme.shapes.medium,
                    color = if (day == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) { DayBlock(d, day, byDay[day].orEmpty(), today, ctx, big = false) }
            }
        }
    }
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
private fun HwLine(d: AppData, it: Archive.Item, showSubject: Boolean, showMeta: Boolean = false) {
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

@Composable
private fun MonthHeader(d: AppData, m: YearMonth, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrev) { Icon(Icons.Default.ChevronLeft, "Прошлый месяц") }
        Text("${d.monthsB.getOrElse(m.monthValue - 1) { "" }.replaceFirstChar { it.uppercase() }} ${m.year}",
            Modifier.weight(1f).clickable(onClick = onToday), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = onNext) { Icon(Icons.Default.ChevronRight, "Следующий месяц") }
    }
}

@Composable
private fun MonthGrid(d: AppData, m: YearMonth, byDay: Map<LocalDate, List<Archive.Item>>, selected: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth()) {
        (0..6).forEach { i ->
            Text(d.weekdaysShort.getOrElse(i) { "" }, Modifier.weight(1f), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelSmall, color = if (i >= 5) cs.error else cs.outline)
        }
    }
    val first = m.atDay(1)
    val lead = first.dayOfWeek.value - 1
    val cells = lead + m.lengthOfMonth()
    val rows = (cells + 6) / 7
    for (r in 0 until rows) {
        Row(Modifier.fillMaxWidth()) {
            for (c in 0..6) {
                val n = r * 7 + c - lead + 1
                if (n < 1 || n > m.lengthOfMonth()) { Spacer(Modifier.weight(1f).height(52.dp)); continue }
                val day = m.atDay(n)
                val list = byDay[day].orEmpty()
                val holiday = Sched.isHoliday(d, day)
                Column(
                    Modifier.weight(1f).height(52.dp).padding(2.dp).clip(RoundedCornerShape(10.dp))
                        .background(when {
                            day == selected -> cs.primary
                            holiday -> cs.tertiaryContainer.copy(alpha = 0.5f)
                            else -> Color.Transparent
                        })
                        .then(if (day == today && day != selected) Modifier.background(cs.primaryContainer) else Modifier)
                        .clickable { onPick(day) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("$n", fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                        color = if (day == selected) cs.onPrimary else if (c >= 5) cs.error else cs.onSurface)
                    if (list.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        list.take(4).forEach { it2 ->
                            Box(Modifier.size(6.dp).clip(CircleShape).background(
                                if (day == selected) cs.onPrimary else if (it2.rec.none) cs.outline else if (it2.pending) cs.tertiary else cs.primary))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayBlock(d: AppData, day: LocalDate, list: List<Archive.Item>, today: LocalDate, ctx: android.content.Context, big: Boolean) {
    Column(Modifier.fillMaxWidth().padding(if (big) 4.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val r = rel(day, today)
        Text("${prettyDate(d, day)}" + if (r.isNotBlank()) " · $r" else "", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (list.isEmpty()) Text(if (Sched.isHoliday(d, day)) "Каникулы 🎉" else "На этот день ДЗ не записано",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        list.sortedBy { Sched.lessonsOn(d, day).indexOfFirst { l -> l.subjectId == it.rec.subjectId }.let { i -> if (i < 0) 99 else i } }
            .forEach { HwLine(d, it, showSubject = true, showMeta = big) }
    }
}
