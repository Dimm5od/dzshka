@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.Route
import com.ilya.dzshka.SubScreen
import com.ilya.dzshka.data.*
import com.ilya.dzshka.link.LinkCrypto
import com.ilya.dzshka.link.MainLink
import com.ilya.dzshka.work.LinkPollWorker
import com.ilya.dzshka.work.LinkService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun ago(t: Long): String = if (t <= 0) "никогда" else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

/** Ввод кода: основной задаёт (или меняет), помощник подключается. Код нигде не показывается и не хранится. */
@Composable
fun CodeForm(
    title: String,
    explain: String,
    nameLabel: String,
    initialName: String,
    initialServer: String,
    confirmTwice: Boolean,
    hidden: Boolean,
    button: String,
    onLater: (() -> Unit)?,
    onSubmit: suspend (code: String, name: String, server: String) -> String?,
) {
    var code by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(!hidden) }
    var code2 by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(initialName) }
    var server by remember { mutableStateOf(initialServer) }
    var adv by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Hint(explain)
        TextSetting(nameLabel, name, { name = it })
        OutlinedTextField(code, { code = it; error = "" }, label = { Text("Код") }, singleLine = true,
            visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = if (shown) KeyboardType.Text else KeyboardType.Password),
            supportingText = { Text("Не меньше 6 знаков. Большие/маленькие буквы и пробелы не важны.") })
        if (confirmTwice) OutlinedTextField(code2, { code2 = it; error = "" }, label = { Text("Код ещё раз") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        TextButton(onClick = { adv = !adv }) { Text(if (adv) "Скрыть доп. настройки" else "Доп. настройки") }
        if (adv) TextSetting("Сервер связи", server, { server = it }, hint = "По умолчанию https://ntfy.sh. Можно свой ntfy-сервер — тогда такой же адрес у всех.")
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                val c = LinkCrypto.normalize(code)
                error = when {
                    name.isBlank() -> "Напиши имя"
                    c.length < 6 -> "Код слишком короткий"
                    confirmTwice && c != LinkCrypto.normalize(code2) -> "Коды не совпадают"
                    else -> ""
                }
                if (error.isNotBlank()) return@Button
                busy = true
                scope.launch {
                    error = onSubmit(code, name, server.ifBlank { "https://ntfy.sh" }) ?: ""
                    busy = false
                    code = ""; code2 = ""
                }
            }) { Text(button) }
            if (onLater != null) TextButton(onClick = onLater, enabled = !busy) { Text("Позже") }
        }
    }
}

@Composable
fun LinkSetupScreen(onDone: () -> Unit) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    val changing = d.link.active
    SubScreen(if (changing) "Сменить код" else "Код для помощников", onBack = onDone) { pad ->
        Box(Modifier.padding(pad)) {
            CodeForm(
                title = if (changing) "Новый код" else "Придумай код",
                explain = if (changing) "После смены все помощники отключатся — им нужно будет ввести новый код."
                    else "По этому коду к тебе подключаются помощники в приложении «ДЗшка Помощник». Ты вводишь его на их телефоне сам — у них он скрыт точками и посмотреть его нельзя. У тебя код всегда можно посмотреть в «Помощниках».",
                nameLabel = "Твоё имя (его увидят помощники)",
                initialName = d.link.myName,
                initialServer = d.link.server,
                confirmTwice = false,
                hidden = false,
                button = if (changing) "Сменить" else "Готово",
                onLater = if (changing) null else ({ Store.update { it.copy(link = it.link.copy(askLater = true)) }; onDone() }),
            ) { code, name, server ->
                withContext(Dispatchers.Default) { MainLink.setCode(code, name, server) }
                LinkService.sync(ctx)
                Toast.makeText(ctx, "Код задан", Toast.LENGTH_SHORT).show()
                onDone()
                null
            }
        }
    }
}

@Composable
fun HelpersScreen(onBack: () -> Unit, push: (Route) -> Unit) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(3000); tick++ } }
    var del by remember { mutableStateOf<Helper?>(null) }

    SubScreen("Помощники", onBack, actions = {
        IconButton(onClick = { LinkPollWorker.now(ctx); Toast.makeText(ctx, "Проверяю…", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Default.Refresh, "Проверить") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Section("Подключение") {
                    if (!d.link.active) {
                        Text("Код ещё не задан — помощники не могут подключиться.")
                        Button(onClick = { push(Route.LinkSetup) }) { Text("Задать код") }
                    } else {
                        var showCode by remember { mutableStateOf(false) }
                        val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Код: " + if (d.link.code.isBlank()) "задан раньше (не сохранён — смени, чтобы видеть)" else if (showCode) d.link.code else "•".repeat(d.link.code.length.coerceAtMost(12)),
                                Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            if (d.link.code.isNotBlank()) {
                                TextButton(onClick = { showCode = !showCode }) { Text(if (showCode) "скрыть" else "показать") }
                                IconButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(d.link.code)); Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show() }) {
                                    Icon(Icons.Default.ContentCopy, "Копировать")
                                }
                            }
                        }
                        key(tick) {
                            Text("Ты: ${d.link.myName}")
                            Text(if (LinkService.running) "Связь: ${LinkService.state.ifBlank { "подключаюсь…" }}" else "Связь: проверка раз в 15 минут",
                                style = MaterialTheme.typography.bodySmall)
                            Hint("Последний контакт: ${ago(d.link.lastContact)}")
                        }
                        SwitchRow("Постоянная связь", d.link.listenAlways, { v ->
                            Store.update { it.copy(link = it.link.copy(listenAlways = v)) }; Store.saveNow(); LinkService.sync(ctx)
                        }, desc = "ДЗ от помощников приходят сразу. В шторке будет тихое уведомление «На связи». Выключи — будет проверка раз в 15 минут.")
                        FlowRowButtons {
                            OutlinedButton(onClick = { push(Route.LinkSetup) }) { Text("Сменить код") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { runCatching { MainLink.publishCfg(ctx, force = true) } }
                                    Toast.makeText(ctx, if (r.isSuccess) "Расписание разослано" else "Ошибка: ${r.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }) { Text("Разослать расписание") }
                        }
                        Hint("Расписание и звонки уходят помощникам сами, когда ты их меняешь.")
                    }
                }
            }
            item {
                Section("Как подключить помощника") {
                    Text("1. Он ставит приложение «ДЗшка Помощник».\n2. Пишет своё имя и твой код.\n3. Ты получаешь уведомление «Новый помощник» и нажимаешь «Разрешить».")
                }
            }
            item {
                Section("Для новых помощников") {
                    val hd = d.helperDefaults
                    fun upd(f: (HelperDefaults) -> HelperDefaults) = Store.update { it.copy(helperDefaults = f(it.helperDefaults)) }
                    SwitchRow("Разрешать сразу", hd.autoApproveNew, { v -> upd { it.copy(autoApproveNew = v) } },
                        desc = "Иначе новый помощник ждёт, пока ты нажмёшь «Разрешить»")
                    SwitchRow("Проверять каждое ДЗ", hd.needsApproval, { v -> upd { it.copy(needsApproval = v) } },
                        desc = "ДЗ от нового помощника не уходит в канал, пока ты не одобришь")
                    SwitchRow("Уведомлять о ДЗ от помощников", hd.notifyOnHw, { v -> upd { it.copy(notifyOnHw = v) } })
                    Text("Что можно новым помощникам сразу", style = MaterialTheme.typography.labelLarge)
                    Hint("Всегда можно: ДЗ текстом по сегодняшним урокам из расписания. Остальное — галочками.")
                    PermsEditor(hd.perms) { v -> upd { it.copy(perms = v) } }
                }
            }
            item {
                Text("Помощники (${d.helpers.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                if (d.helpers.isEmpty()) Column(Modifier.padding(horizontal = 16.dp)) { Hint("Пока никого.") }
            }
            items(d.helpers.sortedWith(compareBy({ it.status != HelperStatus.PENDING }, { -it.lastSeen })), key = { it.id }) { h ->
                HelperCard(d, h, onDelete = { del = h })
            }
        }
    }
    del?.let { h ->
        ConfirmDialog("Удалить ${h.shownName}?", "Он перестанет быть помощником. Если у него остался код, он сможет попроситься снова — тогда лучше смени код или заблокируй.", "Удалить",
            onYes = { MainLink.removeHelper(h.id) }, onDismiss = { del = null })
    }
}

@Composable
private fun HelperCard(d: AppData, h: Helper, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(h.status == HelperStatus.PENDING) }
    fun upd(f: (Helper) -> Helper) = MainLink.updateHelper(h.id, f)
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.shownName + if (h.nickname.isNotBlank() && h.nickname != h.name) " (${h.name})" else "", fontWeight = FontWeight.SemiBold)
                    Hint("${h.model} · v${h.version} · был ${ago(h.lastSeen)}")
                    Hint("Прислал: ${h.sent} · отклонено: ${h.rejected}" + if (h.cfgVer != 0L && h.cfgVer == d.link.cfgVer) " · расписание свежее" else "")
                    if (h.perms.isNotEmpty()) Hint("Ещё можно: " + h.perms.joinToString(", ") { it.label })
                }
                AssistChip(onClick = { open = !open }, label = {
                    Text(when (h.status) { HelperStatus.ACTIVE -> "✅"; HelperStatus.PENDING -> "⏳"; HelperStatus.BLOCKED -> "⛔" } + " " + h.status.label)
                })
            }
            if (h.status == HelperStatus.PENDING) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { upd { it.copy(status = HelperStatus.ACTIVE) } }) { Text("Разрешить") }
                OutlinedButton(onClick = { upd { it.copy(status = HelperStatus.BLOCKED) } }) { Text("Заблокировать") }
            }
            if (open) {
                Dropdown("Статус", h.status, HelperStatus.entries, { it.label }, { v -> upd { it.copy(status = v) } })
                TextSetting("Как подписывать (токен КТО)", h.nickname, { v -> upd { it.copy(nickname = v) } }, hint = "Пусто — его собственное имя «${h.name}»")
                SwitchRow("Проверять каждое ДЗ", h.needsApproval, { v -> upd { it.copy(needsApproval = v) } },
                    desc = "ДЗ и сообщения ждут твоего «Одобрить», прежде чем уйти в канал")
                Text("Что ему можно", style = MaterialTheme.typography.labelLarge)
                Hint("Всегда: ДЗ текстом по сегодняшним урокам из расписания. Дополнительно:")
                PermsEditor(h.perms) { v -> upd { it.copy(perms = v) } }
                Text("Какие предметы может присылать", style = MaterialTheme.typography.labelLarge)
                val all = h.allowedSubjects.isEmpty()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = all, onClick = { upd { it.copy(allowedSubjects = emptyList()) } }, label = { Text("Все") })
                    d.subjects.forEach { s ->
                        val sel = !all && s.id in h.allowedSubjects
                        FilterChip(selected = sel, onClick = {
                            upd { hh ->
                                val cur = if (hh.allowedSubjects.isEmpty()) emptyList() else hh.allowedSubjects
                                val next = if (sel) cur - s.id else cur + s.id
                                hh.copy(allowedSubjects = next)
                            }
                        }, label = { Text(s.short.ifBlank { s.name }) })
                    }
                }
                TextButton(onClick = onDelete) { Text("Удалить помощника", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
fun PermsEditor(perms: List<HelperPerm>, onChange: (List<HelperPerm>) -> Unit) {
    HelperPerm.entries.forEach { p ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = p in perms, onCheckedChange = { c -> onChange(if (c) perms + p else perms - p) })
            Column(Modifier.weight(1f)) {
                Text(p.label)
                Hint(p.desc)
            }
        }
    }
}
