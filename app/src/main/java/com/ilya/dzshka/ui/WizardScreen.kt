@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.SubScreen
import com.ilya.dzshka.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Step(val title: String, val what: String, val why: String, val body: @Composable () -> Unit = {})

@Composable
fun WizardScreen(onDone: () -> Unit) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }

    val steps = listOf(
        Step(
            "Привет! Это ДЗшка",
            "Нажми «Далее» — за пару минут всё настроим.",
            "Приложение помнит расписание, на каждой перемене спрашивает, что задали, само оформляет пост «ДЗ на 20 октября…» и выкладывает его в твой Viber-канал. Всё потом можно поменять в настройках.",
        ),
        Step(
            "1. Токен канала",
            "Открой Viber → свой канал → нажми на название канала сверху → пролистай вниз до «Developer Tools» (Инструменты разработчика) → «Copy token» (скопировать). Вернись сюда и нажми «Вставить», потом «Проверить токен».",
            "Токен — это ключ, по которому приложение может публиковать посты в твой канал. Получить его может только суперадмин (создатель) канала. Никому его не показывай.",
        ) { ViberSetup(d, showAdvanced = false) },
        Step(
            "2. Включить API",
            "Нажми «Активировать API», а потом «Тестовый пост» — в канале должно появиться сообщение.",
            "Viber разрешает публиковать через приложение только после активации. Если тестовый пост пришёл — всё работает.",
        ) { ViberSetup(d, showAdvanced = false) },
        Step(
            "3. Расписание",
            "Вставь расписание текстом (как в примере) и нажми «Импортировать». Или пропусти и заполни потом во вкладке «Расписание».",
            "По расписанию приложение знает, когда перемена, какой предмет закончился и на какое число задано ДЗ (к следующему уроку этого предмета).",
        ) { WizardImport() },
        Step(
            "4. Звонки",
            "Проверь время уроков. Если не совпадает — нажми «Сгенерировать» и укажи начало, длину урока и перемены.",
            "Напоминание приходит по звонку с урока. Если звонки разные в разные дни — добавь ещё вариант во вкладке «Расписание → Звонки».",
        ) { WizardBells(d) },
        Step(
            "5. Как отправлять",
            "Выбери готовый вариант — как ДЗ будет выглядеть в канале.",
            "Потом во вкладке «Студия» можно собрать что угодно: свои моменты отправки, порядок сообщений, заголовки, списки, тексты.",
        ) {
            Presets.list.forEach { p ->
                val chosen = d.moments.map { it.copy(id = "") }.map { m -> m.copy(steps = m.steps.map { it.copy(id = "") }) } ==
                    p.build().map { it.copy(id = "") }.map { m -> m.copy(steps = m.steps.map { it.copy(id = "") }) }
                OutlinedCard(onClick = { Store.update { it.copy(moments = p.build()) } }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text((if (chosen) "✅ " else "") + p.name, fontWeight = FontWeight.SemiBold)
                        Hint(p.desc)
                    }
                }
            }
            Dropdown("ДЗ на какое число", d.dueMode, DueMode.entries, { it.label }, { v -> Store.update { it.copy(dueMode = v) } })
        },
        Step(
            "6. Разрешения",
            "Нажми каждую кнопку «Включить» и разреши. На Xiaomi обязательно включи «Автозапуск».",
            "Без этого телефон может не будить приложение на переменах, особенно после перезагрузки или когда включена экономия батареи.",
        ) { PermissionsSection() },
        Step(
            "Готово! 🎉",
            "Нажми «Начать». ДЗ можно добавить кнопкой «+ ДЗ», из уведомления на перемене или поделившись фото из галереи.",
            "Всё, что было в мастере, можно поменять в «Настройках». Там же можно сохранить настройки в файл и перенести на другой телефон.",
        ),
    )

    val s = steps[step]
    SubScreen("Настройка ${step + 1}/${steps.size}", onBack = { if (step > 0) step-- else onDone() }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LinearProgressIndicator(progress = { (step + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(s.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
                item {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Что нажать", style = MaterialTheme.typography.labelLarge)
                            Text(s.what)
                        }
                    }
                }
                item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { s.body() } }
                item {
                    Column {
                        Text("Зачем", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Hint(s.why)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Назад") }
                Spacer(Modifier.weight(1f))
                if (step < steps.lastIndex) {
                    TextButton(onClick = { Store.update { it.copy(setupDone = true) }; onDone() }) { Text("Пропустить всё") }
                    Button(onClick = { step++ }) { Text("Далее") }
                } else Button(onClick = {
                    Store.update { it.copy(setupDone = true) }
                    if (Store.d.viber.fromId.isBlank()) Toast.makeText(ctx, "Viber ещё не настроен — ДЗ будет копиться в очереди", Toast.LENGTH_LONG).show()
                    onDone()
                }) { Text("Начать") }
            }
        }
    }
}

@Composable
private fun WizardImport() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    PreviewBox("Пн: Математика, Русский язык, Физика\nВт: Физика, Алгебра, История\n…")
    OutlinedTextField(text, { text = it }, label = { Text("Расписание") }, minLines = 6, modifier = Modifier.fillMaxWidth())
    Button(enabled = text.isNotBlank(), onClick = {
        val r = Sched.importText(Store.d, text, 0)
        Store.update { r.data }
        Toast.makeText(ctx, r.report, Toast.LENGTH_LONG).show()
        text = ""
    }) { Text("Импортировать") }
    val d by Store.data.collectAsState()
    if (d.subjects.isNotEmpty()) Hint("Уже есть: " + (0..6).mapNotNull { i ->
        val ls = d.weeks[0].days[i].lessons.filter { it.isNotBlank() }
        if (ls.isEmpty()) null else d.weekdaysShort[i] + ": " + ls.joinToString(", ") { d.subject(it)?.name ?: "?" }
    }.joinToString("\n"))
}

@Composable
private fun WizardBells(d: AppData) {
    var start by remember { mutableStateOf(d.bells.first().slots.firstOrNull()?.start ?: "08:30") }
    var len by remember { mutableIntStateOf(45) }
    var breaks by remember { mutableStateOf("10, 20, 10, 10, 10, 10") }
    var count by remember { mutableIntStateOf(maxOf(d.bells.first().slots.size, 7)) }
    PreviewBox(d.bells.first().slots.mapIndexed { i, s -> "${i + d.firstLessonNum}. ${s.start}–${s.end}" }.joinToString("\n"))
    TimeButton(start, { start = it }, label = "Первый урок в")
    IntSetting("Длина урока, мин", len, { len = it }, 5..180)
    TextSetting("Перемены по порядку, мин", breaks, { breaks = it }, hint = "через запятую; последняя повторяется")
    IntSetting("Сколько уроков", count, { count = it }, 1..15)
    Button(onClick = {
        val br = breaks.split(',', ' ', ';').mapNotNull { it.trim().toIntOrNull() }
        Store.update { a -> a.copy(bells = a.bells.mapIndexed { i, b -> if (i == 0) b.copy(slots = Defaults.generate(start, len, br, count)) else b }) }
    }) { Text("Сгенерировать") }
}

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val d by Store.data.collectAsState()
    val clip = LocalClipboardManager.current
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    SubScreen("История отправок", onBack, actions = {
        TextButton(onClick = { Store.update { it.copy(history = emptyList()) } }) { Text("Очистить") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (d.history.isEmpty()) item { Column(Modifier.padding(16.dp)) { Hint("Пока пусто") } }
            items(d.history) { h ->
                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text((if (h.ok) "✓ " else "✗ ") + fmt.format(Date(h.time)) + if (h.photos > 0) " · 📷 ${h.photos}" else "",
                                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            IconButton(onClick = { clip.setText(AnnotatedString(h.text)) }, Modifier.size(24.dp)) { Icon(Icons.Default.ContentCopy, "Копировать") }
                        }
                        Text(h.text)
                        if (h.error.isNotBlank()) Text(h.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
