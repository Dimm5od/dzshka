@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.data.*
import java.time.LocalDate

private fun sampleEntries(d: AppData): List<HwEntry> {
    val today = LocalDate.now()
    val s1 = d.subjects.getOrNull(0)
    val s2 = d.subjects.getOrNull(1)
    val s3 = d.subjects.getOrNull(2)
    fun due(s: Subject?) = (s?.let { Sched.dueFor(d, d.dueMode, it.id, today) } ?: today.plusDays(1)).toString()
    return listOf(
        HwEntry(subjectId = s1?.id ?: "", subjectName = s1?.name ?: "Математика", text = "№238, стр. 45", due = due(s1), lessonDate = today.toString(), lessonNum = 2),
        HwEntry(subjectId = s2?.id ?: "", subjectName = s2?.name ?: "Литература", text = "выучить стих", due = due(s2), lessonDate = today.toString(), lessonNum = 3),
        HwEntry(subjectId = s3?.id ?: "", subjectName = s3?.name ?: "История", noHw = true, due = due(s3), lessonDate = today.toString(), lessonNum = 4),
    )
}

@Composable
fun TemplatesScreen(onBack: (() -> Unit)? = null) {
    val d by Store.data.collectAsState()
    val t = d.templates
    val samples = remember(d) { sampleEntries(d) }
    val sample = samples.first()
    val sctx = remember(d) { Builder.ctx(d, sample) }
    fun setT(f: (Templates) -> Templates) = Store.update { it.copy(templates = f(it.templates)) }

    Scaffold(topBar = { TopAppBar(title = { Text("Токены и названия") }, navigationIcon = {
        if (onBack != null) IconButton(onClick = onBack) { Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
    }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Section("Проверка токенов") {
                    var test by remember { mutableStateOf("Ч за М") }
                    var date by remember { mutableStateOf(LocalDate.now().toString()) }
                    TextSetting("Напиши что угодно с токенами", test, { test = it }, singleLine = false)
                    DateButton(date, { date = it }, label = "Дата", format = { prettyDate(d, it) })
                    val dd = Sched.parseDate(date) ?: LocalDate.now()
                    PreviewBox(Builder.r(d, test, sctx.copy(due = dd, lessonDate = dd, today = dd)))
                    Hint("Токены — короткие слова, которые заменяются на значения. Список и что каждый значит — ниже, всё можно менять.")
                }
            }

            item {
                Section("Подстановки для токена Т") {
                    Hint("Сами сообщения (порядок, заголовки, списки) настраиваются во вкладке «Студия».")
                    TplField("Если ДЗ нет", t.noHw, d, sctx) { v -> setT { it.copy(noHw = v) } }
                    TplField("Если только фото без текста", t.emptyText, d, sctx) { v -> setT { it.copy(emptyText = v) } }
                    SwitchRow("Убирать лишние пробелы", t.tidyUp, { v -> setT { it.copy(tidyUp = v) } },
                        desc = "Например, когда у предмета нет эмодзи — не будет пробела в начале строки")
                }
            }

            item {
                Section("Уведомление на перемене") {
                    TplField("Заголовок", t.notifTitle, d, sctx) { v -> setT { it.copy(notifTitle = v) } }
                    TplField("Текст", t.notifText, d, sctx) { v -> setT { it.copy(notifText = v) } }
                    TextButton(onClick = { setT { Templates() } }) { Text("Сбросить эти тексты", color = MaterialTheme.colorScheme.error) }
                }
            }

            item { TokensSection(d) }
            item { NamesSection("Месяцы — список 1 (токен М)", d.monthsA, Defaults.MONTH_PRESETS, 12) { v -> Store.update { it.copy(monthsA = v) } } }
            item { NamesSection("Месяцы — список 2 (токен МЕС)", d.monthsB, Defaults.MONTH_PRESETS, 12) { v -> Store.update { it.copy(monthsB = v) } } }
            item { NamesSection("Дни недели (токен Д)", d.weekdays, Defaults.WD_PRESETS, 7) { v -> Store.update { it.copy(weekdays = v) } } }
            item { NamesSection("Дни недели кратко (токен ДН)", d.weekdaysShort, Defaults.WD_PRESETS, 7) { v -> Store.update { it.copy(weekdaysShort = v) } } }

            item {
                Section("Быстрые фразы при вводе ДЗ") {
                    var txt by remember { mutableStateOf(d.quickPhrases.joinToString("\n")) }
                    TextSetting("По одной на строку", txt, { v ->
                        txt = v
                        Store.update { it.copy(quickPhrases = v.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() }) }
                    }, singleLine = false, minLines = 4)
                }
            }
        }
    }
}

@Composable
private fun TplField(label: String, value: String, d: AppData, c: TplCtx, onChange: (String) -> Unit) {
    TextSetting(label, value, onChange, singleLine = false)
    if (value.isNotBlank()) Text("→ " + Builder.r(d, value, c), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
}

private val dateTypes = setOf(
    TokenType.DAY, TokenType.DAY2, TokenType.MONTH_A, TokenType.MONTH_B, TokenType.MONTH_NUM,
    TokenType.MONTH_NUM2, TokenType.YEAR, TokenType.YEAR2, TokenType.WEEKDAY, TokenType.WEEKDAY_SHORT,
)

@Composable
private fun TokensSection(d: AppData) {
    fun upd(id: String, f: (TokenDef) -> TokenDef) = Store.update { a -> a.copy(tokens = a.tokens.map { if (it.id == id) f(it) else it }) }
    var reset by remember { mutableStateOf(false) }
    Section("Токены") {
        SwitchRow("Только отдельным словом", d.tokenWholeWord, { v -> Store.update { it.copy(tokenWholeWord = v) } },
            desc = "Тогда «М» не сработает внутри «Математика», а «Д» — внутри «ДЗ»")
        SwitchRow("Учитывать большие/маленькие буквы", d.tokenCase, { v -> Store.update { it.copy(tokenCase = v) } },
            desc = "Включено: «ч» — это просто буква, токен только «Ч»")
        Hint("Чтобы написать букву-токен как обычный текст, поставь перед ней \\ (например \\Ч). \\n — перенос строки.")
        d.tokens.forEach { tk ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(tk.text, { v -> upd(tk.id) { it.copy(text = v) } }, label = { Text("Токен") }, singleLine = true, modifier = Modifier.width(96.dp))
                Dropdown("Значит", tk.type, TokenType.entries, { it.label }, { v -> upd(tk.id) { it.copy(type = v) } }, Modifier.weight(1f))
                IconButton(onClick = { Store.update { a -> a.copy(tokens = a.tokens.filterNot { it.id == tk.id }) } }) { Icon(Icons.Default.Close, "Удалить") }
            }
            if (tk.type in dateTypes) Dropdown("Какая дата", tk.source, DateSource.entries, { it.label }, { v -> upd(tk.id) { it.copy(source = v) } })
            if (tk.type == TokenType.CUSTOM) TextSetting("Текст", tk.value, { v -> upd(tk.id) { it.copy(value = v) } })
        }
        FlowRowButtons {
            Button(onClick = { Store.update { it.copy(tokens = it.tokens + TokenDef(text = "{новый}", type = TokenType.CUSTOM)) } }) { Text("+ токен") }
            TextButton(onClick = { reset = true }) { Text("Вернуть стандартные") }
        }
    }
    if (reset) ConfirmDialog("Вернуть стандартные токены?", "Твои токены удалятся.", onYes = { Store.update { it.copy(tokens = Defaults.tokens()) } }, onDismiss = { reset = false })
}

@Composable
private fun NamesSection(title: String, values: List<String>, presets: List<Pair<String, List<String>>>, count: Int, onChange: (List<String>) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Section(title) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(values.take(count).joinToString(", "), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open = !open }) { Text(if (open) "Скрыть" else "Изменить") }
        }
        if (open) {
            FlowRowButtons {
                presets.filter { it.second.size == count }.forEach { (name, list) ->
                    SuggestionChip(onClick = { onChange(list) }, label = { Text(name) })
                }
            }
            val padded = values + List((count - values.size).coerceAtLeast(0)) { "" }
            padded.take(count).chunked(2).forEachIndexed { row, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEachIndexed { k, v ->
                        val idx = row * 2 + k
                        OutlinedTextField(v, { nv -> onChange(padded.take(count).toMutableList().also { it[idx] = nv }) },
                            label = { Text("${idx + 1}") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
