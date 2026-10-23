@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import android.Manifest
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as CColor
import com.ilya.dzshka.Route
import com.ilya.dzshka.data.*
import com.ilya.dzshka.net.AccountInfo
import com.ilya.dzshka.net.Uploader
import com.ilya.dzshka.net.Viber
import com.ilya.dzshka.work.Alarms
import com.ilya.dzshka.work.Notif
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.LocalDateTime

@Composable
fun SettingsScreen(push: (Route) -> Unit) {
    val d by Store.data.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                Section("Разделы") {
                    val pending = d.helpers.count { it.status == HelperStatus.PENDING }
                    FlowRowButtons {
                        Button(onClick = { push(Route.Helpers) }) {
                            Text("👥 Помощники (${d.helpers.size})" + if (pending > 0) " · ждут: $pending" else "")
                        }
                        OutlinedButton(onClick = { push(Route.Templates) }) { Text("Токены и названия") }
                        OutlinedButton(onClick = { push(Route.History) }) { Text("История") }
                        OutlinedButton(onClick = { push(Route.Browser) }) { Text("🌐 Браузер") }
                    }
                    if (!d.link.active) Hint("Код для помощников ещё не задан — задай его в «Помощники».")
                }
            }
            item { Section("Viber-канал") { ViberSetup(d) } }
            item {
                Section("🧪 Тест-канал") {
                    Hint("Второй канал для проверок. Там, где отправляешь сам (Написать, Отправить сейчас, запись ДЗ), появится галочка «🧪 в тест-канал». Плановые отправки всегда идут в основной канал.")
                    ViberSetup(d, showAdvanced = false, test = true)
                }
            }
            item { SendSection(d) }
            item { PhotoSection(d) }
            item { ReminderSection(d) }
            item { ChatHwSection(d) }
            item { GithubSection(d) }
            item { com.ilya.dzshka.mascot.WidgetSection(d) }
            item { com.ilya.dzshka.mascot.CatSection(d) }
            item { PermissionsSection() }
            item { LookSection(d) }
            item { DataSection(d, push) }
            item { UpdateSection() }
        }
    }
}

// ---------------- Viber ----------------

@Composable
fun ViberSetup(d: AppData, showAdvanced: Boolean = true, test: Boolean = false) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = if (test) d.testViber else d.viber
    fun upd(f: (ViberCfg) -> ViberCfg) = Store.update { if (test) it.copy(testViber = f(it.testViber)) else it.copy(viber = f(it.viber)) }
    var show by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<AccountInfo?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun io(label: String, block: () -> String) {
        busy = true; status = "$label…"
        scope.launch {
            status = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "✗ " + (it.message ?: it.javaClass.simpleName) } }
            busy = false
        }
    }

    OutlinedTextField(
        value = v.token,
        onValueChange = { s -> upd { it.copy(token = s.trim()) } },
        label = { Text(if (test) "Токен тест-канала" else "Токен канала") },
        singleLine = true,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "скрыть" else "показать") } },
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRowButtons {
        OutlinedButton(onClick = {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) upd { it.copy(token = t) } else Toast.makeText(ctx, "Буфер пустой", Toast.LENGTH_SHORT).show()
        }) { Text("Вставить") }
        Button(enabled = v.token.isNotBlank() && !busy, onClick = {
            io("Проверяю") {
                val i = Viber.accountInfo(v.token)
                info = i
                val supers = i.members.filter { it.role.equals("superadmin", true) }.ifEmpty { i.members }
                val pick = supers.firstOrNull { it.id == v.fromId } ?: supers.firstOrNull()
                upd { it.copy(channelName = i.name, fromId = pick?.id ?: it.fromId, fromName = pick?.name ?: it.fromName) }
                "✓ Канал «${i.name}». Отправитель: ${pick?.name ?: "не найден"}" +
                    if (i.webhook.isBlank()) "\nAPI ещё не активирован — нажми «Активировать API»." else ""
            }
        }) { Text("Проверить токен") }
    }
    if (v.channelName.isNotBlank()) Text("Канал: ${v.channelName}" + if (v.fromName.isNotBlank()) " · пишет: ${v.fromName}" else "")
    info?.let { i ->
        if (i.members.size > 1) Dropdown("Кто публикует", v.fromId, i.members.map { it.id },
            { id -> i.members.firstOrNull { it.id == id }?.let { "${it.name} (${it.role})" } ?: id },
            { id -> upd { it.copy(fromId = id, fromName = i.members.firstOrNull { m -> m.id == id }?.name ?: "") } })
    }
    FlowRowButtons {
        OutlinedButton(enabled = v.token.isNotBlank() && !busy, onClick = {
            io("Активирую") { Viber.setWebhook(v.token, v.webhookUrl); "✓ API канала активирован" }
        }) { Text("Активировать API") }
        OutlinedButton(enabled = v.token.isNotBlank() && v.fromId.isNotBlank() && !busy, onClick = {
            io("Отправляю тест") {
                Viber.postText(v.token, v.fromId, (if (test) "🧪 " else "") + Builder.r(Store.d, "✅ ДЗшка подключена — СЧ СМ, ВР", TplCtx()), v.minApi)
                "✓ Тестовый пост отправлен — проверь канал"
            }
        }) { Text("Тестовый пост") }
        OutlinedButton(enabled = !busy, onClick = { io("Проверяю связь") { Viber.diagnose(v.token) } }) { Text("Проверить связь") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (status.isNotBlank()) Text(status, color = if (status.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    if (showAdvanced) {
        TextSetting("Адрес для активации API (webhook)", v.webhookUrl, { s -> upd { it.copy(webhookUrl = s.trim()) } },
            hint = "Viber требует любой https-адрес, который отвечает «200 OK». Можно поставить свой сервер.")
        TextSetting("ID отправителя (вручную)", v.fromId, { s -> upd { it.copy(fromId = s.trim()) } })
        IntSetting("Максимум символов в одном посте", v.maxTextLen, { n -> upd { it.copy(maxTextLen = n) } }, 100..7000, hint = "Длинное ДЗ разобьётся на несколько постов")
        IntSetting("min_api_version", v.minApi, { n -> upd { it.copy(minApi = n) } }, 1..20, hint = "Обычно 1. Не трогай, если не знаешь зачем.")
    }
}

// ---------------- Отправка ----------------

@Composable
private fun SendSection(d: AppData) {
    val s = d.send
    fun upd(f: (SendCfg) -> SendCfg) = Store.update { it.copy(send = f(it.send)) }
    Section("Отправка") {
        Hint("Когда и в каком виде ДЗ уходит в канал — во вкладке «Студия».")
        Dropdown("ДЗ по умолчанию на дату", d.dueMode, DueMode.entries, { it.label }, { v -> Store.update { it.copy(dueMode = v) } })
        SwitchRow("Показывать пост перед отправкой", s.confirm, { v -> upd { it.copy(confirm = v) } })
        SwitchRow("Уведомлять об отправке", s.notifyResult, { v -> upd { it.copy(notifyResult = v) } }, desc = "Когда приложение закрыто. Об ошибках уведомляю всегда.")
        SwitchRow("Удалять фото с телефона после отправки", s.deleteSentPhotos, { v -> upd { it.copy(deleteSentPhotos = v) } }, desc = "Только копии внутри приложения, галерея не трогается")
        IntSetting("Номер первого урока", d.firstLessonNum, { n -> Store.update { it.copy(firstLessonNum = n) } }, 0..1, hint = "0 — если есть нулевой урок")
    }
}

// ---------------- Фото ----------------

@Composable
private fun PhotoSection(d: AppData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = d.photo
    fun upd(f: (PhotoCfg) -> PhotoCfg) = Store.update { it.copy(photo = f(it.photo)) }
    var status by remember { mutableStateOf("") }
    Section("Фото") {
        Hint("Viber берёт картинку только по ссылке, поэтому фото сначала загружается на хостинг.")
        Dropdown("Куда загружать", p.hosting, Hosting.entries, { it.label }, { v -> upd { it.copy(hosting = v) } })
        when (p.hosting) {
            Hosting.CATBOX -> TextSetting("userhash (необязательно)", p.catboxUserhash, { v -> upd { it.copy(catboxUserhash = v) } }, hint = "Если есть аккаунт catbox — фото будут в нём")
            Hosting.LITTERBOX -> Dropdown("Сколько хранить", p.litterTime, listOf("1h", "12h", "24h", "72h"), { it }, { v -> upd { it.copy(litterTime = v) } })
            Hosting.IMGBB -> TextSetting("Ключ imgbb", p.imgbbKey, { v -> upd { it.copy(imgbbKey = v.trim()) } }, hint = "Бесплатно: api.imgbb.com → Get API key")
            Hosting.CUSTOM -> {
                TextSetting("Адрес загрузки (POST multipart)", p.customUrl, { v -> upd { it.copy(customUrl = v.trim()) } })
                TextSetting("Имя поля с файлом", p.customField, { v -> upd { it.copy(customField = v.trim()) } })
                TextSetting("Доп. поля формы (ключ=значение)", p.customExtraFields, { v -> upd { it.copy(customExtraFields = v) } }, singleLine = false)
                TextSetting("Заголовки (Имя: значение)", p.customHeaders, { v -> upd { it.copy(customHeaders = v) } }, singleLine = false)
                TextSetting("Где ссылка в JSON-ответе", p.customJsonPath, { v -> upd { it.copy(customJsonPath = v.trim()) } }, hint = "например data.url; пусто — ответ и есть ссылка")
            }
        }
        IntSetting("Макс. размер стороны, px", p.maxSide, { n -> upd { it.copy(maxSide = n) } }, 320..6000)
        IntSetting("Качество JPEG, %", p.quality, { n -> upd { it.copy(quality = n) } }, 30..100)
        IntSetting("Макс. вес фото, КБ", p.maxKb, { n -> upd { it.copy(maxKb = n) } }, 50..1000, hint = "Viber принимает до 1 МБ")
        OutlinedButton(onClick = {
            status = "Загружаю тестовую картинку…"
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    runCatching {
                        val f = File(ctx.cacheDir, "dz_test.jpeg")
                        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).apply {
                            drawColor(Color.rgb(63, 111, 216))
                            drawText("ДЗшка тест", 40f, 110f, Paint().apply { color = Color.WHITE; textSize = 48f; isAntiAlias = true })
                        }
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        "✓ " + Uploader.upload(f, Store.d.photo)
                    }.getOrElse { "✗ " + it.message }
                }
            }
        }) { Text("Проверить загрузку") }
        if (status.isNotBlank()) Text(status, color = if (status.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}

// ---------------- ДЗ на GitHub ----------------

@Composable
private fun GithubSection(d: AppData) {
    val scope = rememberCoroutineScope()
    val g = d.gh
    fun upd(f: (GhCfg) -> GhCfg) = Store.update { it.copy(gh = f(it.gh)) }
    var show by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Section("☁ ДЗ на GitHub") {
        SwitchRow("Хранить всё ДЗ на GitHub", g.enabled, { v -> upd { it.copy(enabled = v, dirty = v) } },
            desc = "Файл ${g.path} в репозитории ${g.owner}/${g.repo}. Отсюда вкладка «ДЗ» восстановится, даже если переставить приложение.")
        if (g.enabled) {
            OutlinedTextField(
                value = g.token, onValueChange = { s -> upd { it.copy(token = s.trim(), dirty = true) } },
                label = { Text("Токен GitHub (для записи)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "скрыть" else "показать") } },
            )
            Hint("Без токена ДЗ только читается с GitHub, а хранится на телефоне. Токен: github.com → Settings → Developer settings → Fine-grained tokens → репозиторий ${g.repo}, право Contents: Read and write.")
            FlowRowButtons {
                Button(enabled = !busy, onClick = {
                    busy = true; status = "Синхронизирую…"
                    scope.launch {
                        status = withContext(Dispatchers.IO) { runCatching { com.ilya.dzshka.net.GhSync.sync() }.getOrElse { "✗ " + (it.message ?: "ошибка") } }
                        busy = false
                    }
                }) { Text("Синхронизировать сейчас") }
                TextButton(onClick = { more = !more }) { Text(if (more) "Скрыть адрес" else "Другой репозиторий…") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val st = status.ifBlank {
                when {
                    g.lastError.isNotBlank() -> "✗ " + g.lastError
                    g.lastSync > 0 -> "Последняя синхронизация: " + java.text.SimpleDateFormat("d.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(g.lastSync))
                    else -> ""
                }
            }
            if (st.isNotBlank()) Text(st, color = if (st.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Hint("Записей в архиве: ${d.archive.count { !it.deleted }}")
            if (more) {
                TextSetting("Владелец", g.owner, { v -> upd { it.copy(owner = v.trim()) } })
                TextSetting("Репозиторий", g.repo, { v -> upd { it.copy(repo = v.trim()) } })
                TextSetting("Ветка", g.branch, { v -> upd { it.copy(branch = v.trim()) } })
                TextSetting("Файл", g.path, { v -> upd { it.copy(path = v.trim()) } })
            }
        }
    }
}

// ---------------- ДЗ из чата ----------------

@Composable
private fun ChatHwSection(d: AppData) {
    val ctx = LocalContext.current
    val c = d.chatHw
    fun upd(f: (ChatHwCfg) -> ChatHwCfg) = Store.update { it.copy(chatHw = f(it.chatHw)) }
    var access by remember { mutableStateOf(com.ilya.dzshka.work.ChatListener.hasAccess(ctx)) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) access = com.ilya.dzshka.work.ChatListener.hasAccess(ctx)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Section("💬 ДЗ из канала Viber") {
        SwitchRow("Считать ДЗ из канала", c.enabled, { v -> upd { it.copy(enabled = v) } },
            desc = "Если сегодня в канале появилось сообщение с названием предмета, который сегодня есть, — ДЗ из него считается отправленным, как из приложения: текст ДЗ без названия предмета, «✓ отправлено», история. Напоминания по уроку не будет, второй раз ничего не уйдёт.")
        if (c.enabled) {
            if (!access) {
                Text("Нужен доступ к уведомлениям — так ДЗшка видит сообщения Viber.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { com.ilya.dzshka.work.ChatListener.askAccess(ctx) }) { Text("Дать доступ") }
            } else Text("✓ Доступ к уведомлениям есть", color = MaterialTheme.colorScheme.primary)
            if (d.viber.channelName.isBlank()) Text("Сначала вставь токен канала и нажми «Проверить токен» — ДЗшка читает именно этот канал.", color = MaterialTheme.colorScheme.error)
            else Text("Читаю канал «${d.viber.channelName}» — тот, чей токен вставлен выше.")
            Hint("Свои посты Viber тебе уведомлением не показывает — ДЗшка видит посты других админов канала.")
            Hint("Если у канала в Viber выключены уведомления, ДЗшка его не увидит.")
        }
    }
}

// ---------------- Напоминания ----------------

@Composable
private fun ReminderSection(d: AppData) {
    val ctx = LocalContext.current
    val r = d.reminder
    fun upd(f: (ReminderCfg) -> ReminderCfg) = Store.update { it.copy(reminder = f(it.reminder)) }
    Section("Напоминания на переменах") {
        SwitchRow("Напоминать", r.enabled, { v -> upd { it.copy(enabled = v) } })
        if (!com.ilya.dzshka.link.Link.isHelper) ReminderStylePicker(d)
        ExtendedEditor(d)
        IntSetting("Сдвиг от конца урока, мин", r.offsetMin, { n -> upd { it.copy(offsetMin = n) } }, -30..60,
            hint = "0 — со звонком, 2 — через 2 мин после, -3 — за 3 мин до конца")
        SwitchRow("После последнего урока тоже", r.remindLast, { v -> upd { it.copy(remindLast = v) } })
        SwitchRow("Сдвоенный урок — одно напоминание", r.mergeDouble, { v -> upd { it.copy(mergeDouble = v) } })
        IntSetting("«Напомнить» — через сколько минут", r.snoozeMin, { n -> upd { it.copy(snoozeMin = n) } }, 1..120)
        SwitchRow("Звук", r.sound, { v -> upd { it.copy(sound = v) } })
        SwitchRow("Вибрация", r.vibrate, { v -> upd { it.copy(vibrate = v) } })
        Hint("Тексты, кнопки и вид сообщения/экранчика — в «Студии».")
        Hint("Предметы без напоминаний и «не присылать» (физра и т.п.) — в «Расписание → Предметы» или 🚫 в карточке дня.")
        FlowRowButtons {
            OutlinedButton(onClick = {
                val s = Store.d.subjects.firstOrNull()
                if (s == null) Toast.makeText(ctx, "Сначала добавь предметы", Toast.LENGTH_SHORT).show()
                else Notif.remind(ctx, LocalDate.now(), 99, 1, s.id)
            }) { Text(if (d.reminder.style == ReminderStyle.POPUP && !com.ilya.dzshka.link.Link.isHelper) "Показать тестовый экранчик" else "Тестовое сообщение") }
        }
        val plan = remember(d) { Sched.reminders(d, LocalDateTime.now()).take(3) }
        if (plan.isNotEmpty()) Hint("Ближайшие: " + plan.joinToString("; ") {
            "${prettyDate(d, it.time.toLocalDate())} ${Time.fmt(it.time.hour * 60 + it.time.minute)} ${d.subject(it.lesson.subjectId)?.name}"
        })
    }
}

@Composable
fun PermissionsSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    // перечитать состояние, когда пользователь вернулся из настроек
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    key(tick) {
        val notif = Notif.canPost(ctx)
        val exact = Alarms.canExact(ctx)
        val battery = Perms.ignoringBattery(ctx)
        Section("Разрешения (чтобы напоминания приходили вовремя)") {
            PermRow("Уведомления", notif, "Без них не будет напоминаний") {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else Perms.notifSettings(ctx)
            }
            val helper = com.ilya.dzshka.link.Link.isHelper
            if (!exact) PermRow("Точные будильники", exact, "Чтобы напоминание пришло ровно в перемену, а не через 10 минут") { Perms.exactAlarms(ctx) }
            if (!helper) PermRow("Без ограничений батареи", battery, "Иначе система может «усыпить» приложение") { Perms.askBattery(ctx) }
            if (helper) Hint("Помощнику нужно только разрешение на уведомления — больше ничего.")
            if (!helper && Store.d.reminder.style == ReminderStyle.POPUP) {
                PermRow("Поверх других приложений", Perms.canOverlay(ctx), "Чтобы окно «Урок закончился» всплывало сверху на включённом экране") { Perms.askOverlay(ctx) }
                PermRow("Полноэкранные уведомления", Perms.canFullScreen(ctx), "Чтобы окно показывалось на заблокированном экране") { Perms.askFullScreen(ctx) }
                if (Perms.isXiaomi()) PermRow("Xiaomi: экран блокировки и всплывающие окна", null,
                    "В «Другие разрешения» включи «Экран блокировки» и «Всплывающие окна, когда приложение в фоне»") { Perms.xiaomiPerms(ctx) }
            }
            if (!helper && Perms.isXiaomi()) {
                PermRow("Автозапуск (Xiaomi)", null, "На Xiaomi обязательно включи, иначе после перезагрузки напоминаний не будет") { Perms.autostart(ctx) }
            }
            TextButton(onClick = { Perms.notifSettings(ctx) }) { Text("Настройки уведомлений приложения") }
        }
    }
}

@Composable
private fun PermRow(label: String, ok: Boolean?, why: String, onClick: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text((when (ok) { true -> "✅ "; false -> "⚠️ "; null -> "▫️ " }) + label)
            Hint(why)
        }
        if (ok != true) TextButton(onClick = onClick) { Text(if (ok == null) "Открыть" else "Включить") }
    }
}

// ---------------- Вид ----------------

@Composable
private fun LookSection(d: AppData) {
    val l = d.look
    fun upd(f: (LookCfg) -> LookCfg) = Store.update { it.copy(look = f(it.look)) }
    Section("Внешний вид") {
        Dropdown("Тема", l.theme, ThemeMode.entries, { it.label }, { v -> upd { it.copy(theme = v) } })
        if (Build.VERSION.SDK_INT >= 31) SwitchRow("Цвета из обоев (Material You)", l.dynamic, { v -> upd { it.copy(dynamic = v) } })
        if (!l.dynamic || Build.VERSION.SDK_INT < 31) {
            Text("Цвет", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ACCENTS.forEach { c ->
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(CColor(c))
                            .border(if (c == l.accent) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .clickable { upd { it.copy(accent = c) } }
                    )
                }
            }
        }
    }
}

// ---------------- Данные ----------------

@Composable
private fun DataSection(d: AppData, push: (Route) -> Unit) {
    val ctx = LocalContext.current
    var withToken by remember { mutableStateOf(false) }
    var resetAsk by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(Store.exportJson(withToken).toByteArray()) } }
            .onSuccess { Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Ошибка: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val txt = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull().orEmpty()
        Store.importJson(txt)
            .onSuccess { Toast.makeText(ctx, "Настройки загружены", Toast.LENGTH_SHORT).show() }
            .onFailure { Toast.makeText(ctx, "Не тот файл: ${it.message}", Toast.LENGTH_LONG).show() }
    }
    Section("Данные") {
        SwitchRow("Сохранять вместе с токеном и ключами", withToken, { withToken = it }, desc = "Не отправляй такой файл другим — по токену можно писать в твой канал")
        FlowRowButtons {
            OutlinedButton(onClick = { export.launch("dzshka-${LocalDate.now()}.json") }) { Text("Сохранить настройки в файл") }
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Загрузить из файла") }
        }
        Hint("Так можно перенести всё (расписание, шаблоны, токены) на другой телефон или однокласснику.")
        IntSetting("Сколько записей хранить в истории", d.historyLimit, { n -> Store.update { it.copy(historyLimit = n, history = it.history.take(n)) } }, 0..1000)
        FlowRowButtons {
            OutlinedButton(onClick = { push(Route.History) }) { Text("История отправок") }
            OutlinedButton(onClick = { push(Route.Wizard) }) { Text("Мастер настройки") }
            TextButton(onClick = { resetAsk = true }) { Text("Сбросить всё", color = MaterialTheme.colorScheme.error) }
        }
        val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        Hint("ДЗшка $ver")
    }
    if (resetAsk) ConfirmDialog("Сбросить всё?", "Удалятся расписание, шаблоны, токен и очередь.", "Сбросить",
        onYes = {
            Store.photosDir().listFiles()?.forEach { it.delete() }
            Store.replaceAll(AppData())
        }, onDismiss = { resetAsk = false })
}

/** Выбор: напоминание сообщением или экранчиком. */
@Composable
fun ReminderStylePicker(d: AppData) {
    Text("Как напоминать", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReminderStyle.entries.forEach { st ->
            FilterChip(selected = d.reminder.style == st, onClick = { Store.update { it.copy(reminder = it.reminder.copy(style = st)) } },
                label = { Text(st.label) })
        }
    }
    Hint(d.reminder.style.desc)
}
