@file:OptIn(ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.data.*
import com.ilya.dzshka.work.Notif
import java.time.LocalDate

/** Студия → «Напоминание на перемене»: сообщение и экранчик — тексты, кнопки, вид. */
@Composable
fun ReminderStudioSection(d: AppData) {
    val ctx = LocalContext.current
    val r = d.reminder
    fun upd(f: (ReminderCfg) -> ReminderCfg) = Store.update { it.copy(reminder = f(it.reminder)) }
    fun updScreen(f: (PopupCfg) -> PopupCfg) = upd { it.copy(screen = f(it.screen)) }
    fun updT(f: (Templates) -> Templates) = Store.update { it.copy(templates = f(it.templates)) }

    // пример: первый урок ближайшего учебного дня
    val sample = remember(d) {
        val today = LocalDate.now()
        val date = if (Sched.isSchoolDay(d, today)) today else Sched.nextSchoolDay(d, today)
        val l = Sched.lessonsOn(d, date).firstOrNull()
        listOf(date, l?.number ?: 1, l?.subjectId ?: d.subjects.firstOrNull()?.id ?: "", l?.index ?: 0)
    }
    val date = sample[0] as LocalDate
    val num = sample[1] as Int
    val sid = sample[2] as String
    val idx = sample[3] as Int
    val c = Notif.reminderCtx(d, date, num, sid)

    Section("🔔 Напоминание на перемене") {
        ReminderStylePicker(d)
        IntSetting("Через сколько минут после звонка", r.offsetMin, { n -> upd { it.copy(offsetMin = n) } }, -30..60)
        OutlinedButton(onClick = {
            if (sid.isBlank()) Toast.makeText(ctx, "Сначала добавь предметы", Toast.LENGTH_SHORT).show()
            else Notif.remind(ctx, date, idx, num, sid)
        }) { Text("▶ Показать как будет") }
        ExtendedEditor(d)

        HorizontalDivider()
        Text("✉️ Сообщение", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (r.style != ReminderStyle.NOTIFICATION) Hint("Сейчас выбран экранчик. Сообщение приходит, только если экранчик показать нельзя (например, нет разрешения).")
        TextSetting("Заголовок", d.templates.notifTitle, { v -> updT { it.copy(notifTitle = v) } }, singleLine = false)
        TextSetting("Текст", d.templates.notifText, { v -> updT { it.copy(notifText = v) } }, singleLine = false)
        Text("Кнопки (в сообщении помещается не больше 3)", style = MaterialTheme.typography.labelLarge)
        ButtonsEditor(r.notifButtons, max = 3) { v -> upd { it.copy(notifButtons = v) } }
        IntSetting("«Напомнить» — через сколько минут", r.snoozeMin, { n -> upd { it.copy(snoozeMin = n) } }, 1..120)
        IntSetting("Убрать сообщение через, мин", r.autoCancelMin, { n -> upd { it.copy(autoCancelMin = n) } }, 0..600, hint = "0 — не убирать")
        SwitchRow("Звук", r.sound, { v -> upd { it.copy(sound = v) } })
        SwitchRow("Вибрация", r.vibrate, { v -> upd { it.copy(vibrate = v) } })
        Text("Как будет выглядеть:", style = MaterialTheme.typography.labelMedium)
        val extra = Notif.extraLines(d, date, idx, sid, null)
        NotifPreview(Builder.r(d, d.templates.notifTitle, c), Builder.r(d, d.templates.notifText, c) + if (extra.isNotEmpty()) "\n" + extra.joinToString("\n") else "",
            Notif.visibleButtons(d, r.notifButtons).take(3).map { it.text })

        HorizontalDivider()
        Text("🪟 Экранчик", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val s = r.screen
        Text("Расположение", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PopupPos.entries.forEach { p -> FilterChip(selected = s.position == p, onClick = { updScreen { it.copy(position = p) } }, label = { Text(p.label) }) }
        }
        TextSetting("Верхняя строка", s.label, { v -> updScreen { it.copy(label = v) } })
        TextSetting("Заголовок", s.title, { v -> updScreen { it.copy(title = v) } }, hint = "П — предмет")
        TextSetting("Подпись", s.subtitle, { v -> updScreen { it.copy(subtitle = v) } }, hint = "У — номер урока, Ч М — дата сдачи, ДН — день недели")
        TextSetting("Подсказка в поле ввода", s.hint, { v -> updScreen { it.copy(hint = v) } })
        TextSetting("Кнопка отправки", s.sendLabel, { v -> updScreen { it.copy(sendLabel = v) } })
        SwitchRow("Значок предмета", s.showIcon, { v -> updScreen { it.copy(showIcon = v) } }, desc = "эмодзи предмета в кружке слева")
        SwitchRow("Затемнять фон", s.dim, { v -> updScreen { it.copy(dim = v) } })
        SwitchRow("Первая кнопка большая", s.bigFirst, { v -> updScreen { it.copy(bigFirst = v) } }, desc = "иначе все кнопки в один ряд")
        Text("Кнопки", style = MaterialTheme.typography.labelLarge)
        ButtonsEditor(s.buttons, max = 4) { v -> updScreen { it.copy(buttons = v) } }
        Text("Как будет выглядеть:", style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))) {
            if (sid.isNotBlank()) PopupCard(d, s, date, num, sid, preview = true, index = idx)
            else Box(Modifier.padding(16.dp)) { Hint("Добавь предметы, чтобы увидеть пример") }
        }
    }
}

/** Какие кнопки, в каком порядке и с какой надписью. */
@Composable
fun ButtonsEditor(list: List<RemBtn>, max: Int, onChange: (List<RemBtn>) -> Unit) {
    // все виды кнопок, даже если их нет в списке (старые настройки)
    val all = list.distinctBy { it.kind } + RemBtn.defaults().filter { dflt -> list.none { it.kind == dflt.kind } }.map { it.copy(on = false) }
    val onCount = all.count { it.on }
    all.forEachIndexed { i, b ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = b.on, enabled = b.on || onCount < max, onCheckedChange = { v ->
                onChange(all.mapIndexed { k, x -> if (k == i) x.copy(on = v) else x })
            })
            OutlinedTextField(
                b.label, { v -> onChange(all.mapIndexed { k, x -> if (k == i) x.copy(label = v) else x }) },
                label = { Text(b.kind.desc) }, singleLine = true, modifier = Modifier.weight(1f),
            )
            Column {
                IconButton(onClick = { onChange(all.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowUpward, "Выше")
                }
                IconButton(onClick = { onChange(all.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < all.lastIndex, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowDownward, "Ниже")
                }
            }
        }
    }
}

@Composable
private fun NotifPreview(title: String, text: String, buttons: List<String>) {
    Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("ДЗшка · сейчас", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (buttons.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
                buttons.forEach { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** «Расширенно»: какие доп. строки показывать в сообщении и на экранчике. */
@Composable
fun ExtendedEditor(d: AppData) {
    val r = d.reminder
    fun upd(f: (ExtraInfo) -> ExtraInfo) = Store.update { it.copy(reminder = it.reminder.copy(extra = f(it.reminder.extra))) }
    SwitchRow("Расширенно", r.extended, { v -> Store.update { it.copy(reminder = it.reminder.copy(extended = v)) } },
        desc = "Ещё строки про уроки: какой прошёл, какой дальше, перемена, прошлый урок и его ДЗ")
    if (r.extended) Column(Modifier.padding(start = 12.dp)) {
        val x = r.extra
        SwitchRow("✅ Какой урок прошёл", x.passed, { v -> upd { it.copy(passed = v) } })
        SwitchRow("⏭ Следующий урок", x.next, { v -> upd { it.copy(next = v) } })
        if (x.next) SwitchRow("   …и сколько до него минут", x.timeLeft, { v -> upd { it.copy(timeLeft = v) } })
        SwitchRow("☕ Сколько перемена", x.breakLen, { v -> upd { it.copy(breakLen = v) } })
        SwitchRow("↩ Прошлый урок", x.prev, { v -> upd { it.copy(prev = v) } })
        if (x.prev) SwitchRow("   …и какое по нему ДЗ", x.prevHw, { v -> upd { it.copy(prevHw = v) } })
    }
}
