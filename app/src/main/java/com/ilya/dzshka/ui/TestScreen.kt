@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.SubScreen
import com.ilya.dzshka.data.Store
import com.ilya.dzshka.data.Time
import com.ilya.dzshka.work.TestRun
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun TestScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val d by Store.data.collectAsState()
    val st by TestRun.state.collectAsState()
    var cfg by remember { mutableStateOf(st.cfg) }
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(st.running) {
        view.keepScreenOn = st.running
        onDispose { view.keepScreenOn = false }
    }

    fun dayLabel(t: LocalDateTime) = "${d.weekdays.getOrElse(t.dayOfWeek.value - 1) { "" }.replaceFirstChar { it.uppercase() }}, ${t.dayOfMonth} ${d.monthsA.getOrElse(t.monthValue - 1) { "" }}"

    SubScreen("🧪 Неделя за ${cfg.minutes} мин", onBack) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 48.dp)) {
            if (st.running || st.finished) item {
                Section(if (st.finished) "Тест закончен ✓" else "Идёт тест") {
                    st.vnow?.let { t ->
                        Text(dayLabel(t), style = MaterialTheme.typography.titleMedium)
                        Text(Time.fmt(t.hour * 60 + t.minute), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    }
                    LinearProgressIndicator(progress = { if (st.total == 0) 0f else (st.index + if (st.finished) 1 else 0).toFloat() / st.total }, modifier = Modifier.fillMaxWidth())
                    Hint("Событие ${minOf(st.index + 1, st.total)} из ${st.total} · ДЗ записано: ${st.entries} · сообщений в канал: ${st.posts.size}" +
                        if (st.cfg.realSend) " · реально отправлено: ${st.realSent}" else "")
                    st.current?.let { Text(it.title, fontWeight = FontWeight.SemiBold) }
                    if (st.running) FlowRowButtons {
                        OutlinedButton(onClick = { TestRun.answer(TestRun.Answer.Auto) }, enabled = st.waiting) { Text("⏩ Дальше") }
                        TextButton(onClick = { TestRun.stop(ctx) }) { Text("■ Стоп", color = MaterialTheme.colorScheme.error) }
                    } else TextButton(onClick = { TestRun.reset() }) { Text("Сбросить результаты") }
                    st.errors.forEach { Text("✗ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (st.finished) {
                        val days = st.posts.map { it.first.toLocalDate() }.distinct().size
                        Text("Итог: ДЗ записано ${st.entries} (из них «нет ДЗ» — ${st.noHwCount}), сообщений в канал — ${st.posts.size} за $days дн." +
                            if (st.cfg.realSend) ", реально отправлено — ${st.realSent}" else "")
                        if (st.warnings.isEmpty() && st.errors.isEmpty()) Text("✅ Всё сработало, замечаний нет", color = MaterialTheme.colorScheme.primary)
                        st.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            if (!st.running) item {
                Section("Настройки теста") {
                    Hint("Приложение прокрутит неделю на виртуальных часах: каждый звонок, напоминания и все моменты отправки из студии. Твоя настоящая очередь и отметки «отправлено» не меняются. Пока идёт тест, не закрывай приложение — экран не погаснет.")
                    IntSetting("Сколько минут длится тест", cfg.minutes, { cfg = cfg.copy(minutes = it) }, 1..60)
                    Dropdown("Какая неделя", cfg.nextWeek, listOf(false, true), {
                        val m = TestRun.weekStart(LocalDate.now(), it)
                        (if (it) "Следующая" else "Эта") + " (с ${m.dayOfMonth}.${"%02d".format(m.monthValue)})"
                    }, { cfg = cfg.copy(nextWeek = it) })
                    SwitchRow("Напоминания, как на переменах", cfg.notifications, { cfg = cfg.copy(notifications = it) },
                        desc = "Как выбрано в настройках: " + d.reminder.style.label.lowercase())
                    SwitchRow("Не ответил — придумать ДЗ самому", cfg.autoFill, { cfg = cfg.copy(autoFill = it) },
                        desc = "Иначе урок без ответа останется без ДЗ")
                    val canSend = d.testReady || (d.viber.token.isNotBlank() && d.viber.fromId.isNotBlank())
                    SwitchRow("Отправлять в канал по-настоящему", cfg.realSend && canSend, { cfg = cfg.copy(realSend = it) },
                        desc = if (d.testReady) "Сообщения с пометкой 🧪 придут в тест-канал «${d.testViber.channelName.ifBlank { "тест" }}»"
                            else if (canSend) "⚠ Сообщения с пометкой 🧪 придут в «${d.viber.channelName.ifBlank { "твой канал" }}». Лучше на тестовом канале." else "Сначала настрой Viber")
                    val events = remember(d, cfg.nextWeek) { TestRun.buildEvents(d, TestRun.weekStart(LocalDate.now(), cfg.nextWeek)) }
                    val lessons = events.count { it.kind == TestRun.Kind.LESSON_END }
                    Hint("В неделе: звонков с ДЗ — $lessons, моментов отправки — ${events.size - lessons}. На каждый звонок ≈ ${
                        if (events.isEmpty()) 0 else (cfg.minutes * 60 * 3 / events.map { if (it.kind == TestRun.Kind.LESSON_END) 3 else 1 }.sum())
                    } сек.")
                    Button(onClick = { TestRun.start(ctx, cfg.copy(realSend = cfg.realSend && canSend)) }, enabled = events.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (st.finished) "Запустить ещё раз" else "▶ Запустить")
                    }
                    if (events.isEmpty()) Text("В этой неделе нет уроков со временем — заполни расписание и звонки.", color = MaterialTheme.colorScheme.error)
                }
            }

            if (st.running && st.waiting) item { AnswerCard(st) }

            if (st.posts.isNotEmpty()) item {
                Section("Виртуальный канал") {
                    val byDay = st.posts.groupBy { it.first.toLocalDate() }.toList()
                    var openDay by remember { mutableStateOf<LocalDate?>(null) }
                    // во время теста сверху — текущий день, после теста — по порядку
                    val days = if (st.running) byDay.reversed() else byDay
                    val shown = openDay ?: days.first().first
                    FlowRowButtons {
                        byDay.forEach { (date, list) ->
                            FilterChip(selected = date == shown, onClick = { openDay = date },
                                label = { Text("${d.weekdaysShort.getOrElse(date.dayOfWeek.value - 1) { "" }} · ${list.size}") })
                        }
                    }
                    val list = byDay.firstOrNull { it.first == shown }?.second ?: emptyList()
                    Text(dayLabel(shown.atStartOfDay()), style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterHorizontally))
                    ChannelPreview(list.map { it.second })
                    if (st.running && openDay != null) TextButton(onClick = { openDay = null }) { Text("К текущему дню") }
                }
            }

            if (st.log.isNotEmpty()) item {
                Section("Что происходило") {
                    st.log.takeLast(80).reversed().forEach { l ->
                        Row {
                            Text("${d.weekdaysShort.getOrElse(l.time.dayOfWeek.value - 1) { "" }} ${Time.fmt(l.time.hour * 60 + l.time.minute)}",
                                Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            Text(l.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerCard(st: TestRun.State) {
    var text by remember(st.index) { mutableStateOf("") }
    var left by remember(st.index) { mutableLongStateOf(0L) }
    LaunchedEffect(st.index, st.slotEndsAt) {
        while (true) { left = ((st.slotEndsAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0); delay(250) }
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔔 ${st.current?.title ?: ""}", fontWeight = FontWeight.SemiBold)
            Hint("Что задали? Осталось $left сек" + if (st.cfg.autoFill) ", потом придумаю сам" else ", потом урок останется без ДЗ")
            OutlinedTextField(text, { text = it }, label = { Text("ДЗ") }, modifier = Modifier.fillMaxWidth())
            FlowRowButtons {
                Button(onClick = { TestRun.answer(TestRun.Answer.Text(text)) }, enabled = text.isNotBlank()) { Text("Записать") }
                OutlinedButton(onClick = { TestRun.answer(TestRun.Answer.NoHw) }) { Text("Нет ДЗ") }
                OutlinedButton(onClick = { TestRun.answer(TestRun.Answer.Auto) }) { Text("Придумай сам") }
                TextButton(onClick = { TestRun.answer(TestRun.Answer.Skip) }) { Text("Без ДЗ") }
            }
        }
    }
}
