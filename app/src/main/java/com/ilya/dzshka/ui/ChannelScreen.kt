@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.Route
import com.ilya.dzshka.data.*
import com.ilya.dzshka.net.AccountInfo
import com.ilya.dzshka.net.Viber
import com.ilya.dzshka.work.ChannelLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChannelScreen(push: (Route) -> Unit, onBack: (() -> Unit)? = null) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = LocalClipboardManager.current
    var info by remember { mutableStateOf<AccountInfo?>(null) }
    var err by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var compose by remember { mutableStateOf<Pair<String, String>?>(null) } // заголовок окна, текст
    var showHidden by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    val v = d.viber
    val ready = v.token.isNotBlank() && v.fromId.isNotBlank()

    fun load() {
        if (v.token.isBlank()) return
        loading = true; err = ""
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Viber.accountInfo(v.token) } }
            r.onSuccess { info = it; Store.update { s -> s.copy(viber = s.viber.copy(channelName = it.name)) } }
                .onFailure { err = it.message ?: "ошибка" }
            loading = false
        }
    }
    LaunchedEffect(v.token) { load() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Канал") }, navigationIcon = {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            }, actions = {
                IconButton(onClick = { load() }, enabled = !loading) { Icon(Icons.Default.Refresh, "Обновить") }
            })
        },
        floatingActionButton = {
            if (ready) ExtendedFloatingActionButton(onClick = { compose = "Написать в канал" to "" },
                icon = { Icon(Icons.Default.Edit, null) }, text = { Text("Написать") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            if (v.token.isBlank()) item {
                Section("Канал не подключён") {
                    Text("Вставь токен канала в «Настройки → Viber-канал».")
                }
            }
            item {
                Section(info?.name ?: v.channelName.ifBlank { "Канал" }) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (err.isNotBlank()) Text("✗ $err", color = MaterialTheme.colorScheme.error)
                    info?.let { i ->
                        i.subscribers?.let { Text("Подписчиков: $it") }
                        if (i.uri.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Ссылка: ${i.uri}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(onClick = { clip.setText(AnnotatedString(i.uri)); Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show() }) {
                                    Icon(Icons.Default.ContentCopy, "Копировать")
                                }
                            }
                        }
                        Text("Админы и участники, которых видит API (${i.members.size}):", style = MaterialTheme.typography.labelLarge)
                        i.members.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(m.name.ifBlank { "без имени" }, Modifier.weight(1f))
                                AssistChip(onClick = {}, label = {
                                    Text(when (m.role.lowercase()) { "superadmin" -> "создатель"; "admin" -> "админ"; "" -> "—"; else -> m.role })
                                })
                                if (m.id == v.fromId) Text("  ← ты", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (i.extra.isNotEmpty()) Hint(i.extra.entries.joinToString(" · ") { "${it.key}: ${it.value}" })
                    }
                    Hint("Viber даёт приложению только это: название, ссылку и админов. Обычных подписчиков поимённо API не показывает, менять название и удалять сообщения через API тоже нельзя.")
                    OutlinedButton(onClick = {
                        runCatching { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage("com.viber.voip")!!) }
                            .onFailure { Toast.makeText(ctx, "Viber не найден", Toast.LENGTH_SHORT).show() }
                    }) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Открыть Viber") }
                }
            }

            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Сообщения из приложения", style = MaterialTheme.typography.titleMedium)
                        Hint("Всё, что ДЗшка отправила в канал. Удалить из самого канала можно только в Viber: долгий тап по сообщению → «Удалить».")
                    }
                }
                Row(Modifier.padding(horizontal = 12.dp)) {
                    FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("Показать скрытые") })
                }
            }
            val posts = d.channelPosts.filter { showHidden || !it.hidden }
            if (posts.isEmpty()) item { Column(Modifier.padding(16.dp)) { Hint("Пока пусто") } }
            items(posts, key = { it.time.toString() + it.token + it.text.hashCode() }) { p ->
                ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmt.format(Date(p.time)) + (if (p.photoUrl.isNotBlank()) " · 🖼 фото" else "") + (if (p.author.isNotBlank()) " · от ${p.author}" else "") + if (p.hidden) " · скрыто" else "",
                                Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        }
                        if (p.text.isNotBlank()) Text(p.text)
                        FlowRowButtons {
                            TextButton(onClick = { clip.setText(AnnotatedString(p.text)); Toast.makeText(ctx, "Скопировано", Toast.LENGTH_SHORT).show() }) { Text("Копировать") }
                            if (ready) TextButton(onClick = { compose = "Исправить сообщение" to p.text }) { Text("Исправить") }
                            if (p.photoUrl.isNotBlank()) TextButton(onClick = {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.photoUrl))) }
                            }) { Text("Фото") }
                            TextButton(onClick = {
                                Store.update { s -> s.copy(channelPosts = s.channelPosts.map { if (it === p || (it.time == p.time && it.token == p.token)) it.copy(hidden = !it.hidden) else it }) }
                            }) { Text(if (p.hidden) "Вернуть" else "Скрыть из списка") }
                        }
                    }
                }
            }
        }
    }

    compose?.let { (title, initial) -> ComposeDialog(title, initial, isFix = initial.isNotBlank(), onDismiss = { compose = null }) }
}

@Composable
internal fun ComposeDialog(title: String, initial: String, isFix: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var prefix by remember { mutableStateOf(if (isFix) "✏️ Исправление:\n" else "") }
    var busy by remember { mutableStateOf(false) }
    var toTest by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isFix) {
                    Hint("Viber не даёт менять отправленное. Уйдёт новое сообщение — старое удали в Viber вручную, если нужно.")
                    TextSetting("Начало сообщения", prefix.replace("\n", "\\n"), { prefix = it.replace("\\n", "\n") })
                }
                OutlinedTextField(text, { text = it }, label = { Text("Текст") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                Hint("Токены работают и тут, например СЧ СМ — сегодняшняя дата.")
                TestChannelCheck(toTest) { toTest = it }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    val d = Store.d
                    val msg = Builder.r(d, prefix + text, TplCtx())
                    val test = toTest && d.testReady
                    val v = if (test) d.testViber else d.viber
                    val r = withContext(Dispatchers.IO) { runCatching { Viber.postText(v.token, v.fromId, msg, v.minApi) } }
                    busy = false
                    r.onSuccess { tok ->
                        if (!test) ChannelLog.record(tok, msg, "", "")
                        Store.addHistory(SentRecord(text = (if (test) "🧪 Тест-канал: " else "") + msg, ok = true))
                        Toast.makeText(ctx, "Отправлено", Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }.onFailure { Toast.makeText(ctx, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            }) { Text(if (busy) "Отправляю…" else "Отправить") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Отмена") } },
    )
}

/** Канал на главной: название, «Написать», последние сообщения из приложения, «Весь канал». */
@Composable
fun ChannelHomeSection(d: AppData, push: (Route) -> Unit) {
    var compose by remember { mutableStateOf(false) }
    val v = d.viber
    val ready = v.token.isNotBlank() && v.fromId.isNotBlank()
    val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    Section("📣 " + v.channelName.ifBlank { "Канал" }) {
        if (!ready) Hint("Канал не подключён — вставь токен в «Настройки → Viber-канал».")
        val posts = d.channelPosts.filter { !it.hidden }.take(3)
        if (ready && posts.isEmpty()) Hint("Из приложения в канал пока ничего не уходило.")
        posts.forEach { p ->
            Column(Modifier.fillMaxWidth()) {
                Text(fmt.format(Date(p.time)) + (if (p.photoUrl.isNotBlank()) " · 🖼" else "") + (if (p.author.isNotBlank()) " · ${p.author}" else ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                Text(p.text.ifBlank { "фото" }, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ready) Button(onClick = { compose = true }) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Написать") }
            OutlinedButton(onClick = { push(Route.Channel) }) { Text("Весь канал") }
        }
    }
    if (compose) ComposeDialog("Написать в канал", "", isFix = false, onDismiss = { compose = false })
}
