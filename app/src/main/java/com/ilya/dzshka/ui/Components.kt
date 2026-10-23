@file:OptIn(ExperimentalMaterial3Api::class)

package com.ilya.dzshka.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.data.LookCfg
import com.ilya.dzshka.data.Sched
import com.ilya.dzshka.data.ThemeMode
import com.ilya.dzshka.data.Time
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

val ACCENTS = listOf(
    0xFF3F6FD8, 0xFF6750A4, 0xFF0F9D58, 0xFF00897B, 0xFFE65100,
    0xFFD81B60, 0xFFC62828, 0xFF5D4037, 0xFF455A64, 0xFF1E88E5,
)

@Composable
fun isDark(look: LookCfg): Boolean = when (look.theme) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** @param space ночная тема-космос: фон прозрачный (под ним рисуется космос), карточки полупрозрачные. */
@Composable
fun DzTheme(look: LookCfg, space: Boolean = false, content: @Composable () -> Unit) {
    val dark = isDark(look)
    val ctx = LocalContext.current
    val accent = Color(look.accent)
    val scheme = when {
        look.dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(
            primary = lighten(accent), secondary = lighten(accent), tertiary = lighten(accent),
            primaryContainer = mix(accent, Color(0xFF1A1B1F), 0.55f), onPrimaryContainer = Color.White,
            secondaryContainer = mix(accent, Color(0xFF1A1B1F), 0.65f), onSecondaryContainer = Color.White,
        )
        else -> lightColorScheme(
            primary = accent, secondary = accent, tertiary = accent,
            primaryContainer = mix(accent, Color.White, 0.82f), onPrimaryContainer = Color(0xFF111111),
            secondaryContainer = mix(accent, Color.White, 0.86f), onSecondaryContainer = Color(0xFF111111),
        )
    }
    val final = if (dark && space) scheme.copy(
        background = Color.Transparent,
        surface = Color(0x3308081A),
        surfaceContainerLowest = Color(0x8C0B0C1E),
        surfaceContainerLow = Color(0xB3131430),
        surfaceContainer = Color(0xE00C0D20),
        surfaceContainerHigh = Color(0xFF1B1C38),
        surfaceContainerHighest = Color(0xFF26284A),
        surfaceVariant = Color(0x99242650),
        onSurface = Color(0xFFECEAFF),
        onSurfaceVariant = Color(0xFFC3C0E0),
        outline = Color(0xFF8E8AB5),
    ) else scheme
    MaterialTheme(colorScheme = final, content = content)
}

private fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
)

private fun lighten(c: Color) = Color(
    red = c.red + (1 - c.red) * 0.35f,
    green = c.green + (1 - c.green) * 0.35f,
    blue = c.blue + (1 - c.blue) * 0.35f,
)

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun TextSetting(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    hint: String? = null,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        supportingText = hint?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun IntSetting(label: String, value: Int, onChange: (Int) -> Unit, range: IntRange, hint: String? = null, modifier: Modifier = Modifier) {
    var txt by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = txt,
        onValueChange = { s ->
            txt = s
            s.toIntOrNull()?.let { onChange(it.coerceIn(range)) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = hint?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, desc: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (desc != null) Hint(desc)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> Dropdown(
    label: String,
    value: T,
    options: List<T>,
    text: (T) -> String,
    onChange: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = text(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(text(o)) }, onClick = { onChange(o); open = false })
            }
        }
    }
}

@Composable
fun TimeButton(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = modifier, contentPadding = PaddingValues(horizontal = 10.dp)) {
        Text((label?.let { "$it " } ?: "") + value.ifBlank { "--:--" })
    }
    if (open) {
        val m = Time.parse(value) ?: (8 * 60)
        val st = rememberTimePickerState(m / 60, m % 60, true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onChange(Time.fmt(st.hour * 60 + st.minute)); open = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
            text = { TimePicker(st) },
        )
    }
}

@Composable
fun DateButton(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, label: String = "", format: (LocalDate) -> String = { it.toString() }) {
    var open by remember { mutableStateOf(false) }
    val date = Sched.parseDate(value)
    OutlinedButton(onClick = { open = true }, modifier = modifier) {
        Text((if (label.isNotEmpty()) "$label: " else "") + (date?.let(format) ?: "выбрать"))
    }
    if (open) {
        val init = (date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val st = rememberDatePickerState(initialSelectedDateMillis = init)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Отмена") } },
        ) { DatePicker(st) }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, yes: String = "Да", onYes: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onYes(); onDismiss() }) { Text(yes) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun PreviewBox(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(text.ifEmpty { "(пусто)" }, Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Красивая подпись даты: «пт, 16 октября» по настройкам названий. */
fun prettyDate(d: com.ilya.dzshka.data.AppData, date: LocalDate): String =
    "${d.weekdaysShort.getOrElse(date.dayOfWeek.value - 1) { "" }}, ${date.dayOfMonth} ${d.monthsA.getOrElse(date.monthValue - 1) { "" }}"


/** Галочка «🧪 в тест-канал» у ручных отправок. Если тест-канал не настроен — подсказка. */
@Composable
fun TestChannelCheck(checked: Boolean, onChange: (Boolean) -> Unit) {
    val d by com.ilya.dzshka.data.Store.data.collectAsState()
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.clickable(enabled = d.testReady) { onChange(!checked) },
    ) {
        Checkbox(checked = checked && d.testReady, onCheckedChange = onChange, enabled = d.testReady)
        Text(if (d.testReady) "🧪 в тест-канал «${d.testViber.channelName.ifBlank { "тест" }}»" else "🧪 в тест-канал (настрой в Настройках)",
            style = MaterialTheme.typography.bodyMedium,
            color = if (d.testReady) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}
