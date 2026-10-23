@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.ilya.dzshka.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.ilya.dzshka.Route
import com.ilya.dzshka.SubScreen
import com.ilya.dzshka.data.*
import com.ilya.dzshka.net.Images
import com.ilya.dzshka.work.Entries
import com.ilya.dzshka.work.Sender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

private const val OTHER = "__other__"

@Composable
fun EntryScreen(route: Route.Entry, onClose: () -> Unit) {
    val d by Store.data.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(route.key) { route.editId?.let { id -> Store.d.queue.firstOrNull { it.id == id } } }

    var subjectId by remember(route.key) {
        mutableStateOf(existing?.subjectId?.takeIf { it.isNotBlank() } ?: route.subjectId?.takeIf { d.subject(it) != null }
            ?: d.subjects.firstOrNull()?.id ?: OTHER)
    }
    var otherName by remember(route.key) { mutableStateOf(if (existing != null && existing.subjectId.isBlank()) existing.subjectName else "") }
    var lessonDate by remember(route.key) { mutableStateOf(existing?.lessonDate ?: route.date ?: LocalDate.now().toString()) }
    var lessonNum by remember(route.key) { mutableIntStateOf(existing?.lessonNum ?: route.num) }
    var dueMode by remember(route.key) { mutableStateOf<DueMode?>(if (existing != null) null else d.dueMode) }
    var customDue by remember(route.key) { mutableStateOf(existing?.due ?: "") }
    var text by remember(route.key) {
        val t = existing?.text ?: route.sharedText
        mutableStateOf(TextFieldValue(t, TextRange(t.length)))
    }
    var noHw by remember(route.key) { mutableStateOf(existing?.noHw ?: false) }
    val photos = remember(route.key) { mutableStateListOf<String>().apply { existing?.photos?.let { addAll(it) } } }
    val newPhotos = remember(route.key) { mutableListOf<String>() }
    var toTest by remember(route.key) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmText by remember { mutableStateOf<String?>(null) }

    val ld = Sched.parseDate(lessonDate) ?: LocalDate.now()
    val sid = if (subjectId == OTHER) "" else subjectId
    val due: LocalDate = dueMode?.let { Sched.dueFor(d, it, sid, ld) } ?: Sched.parseDate(customDue) ?: ld

    // если урок выбран из расписания — подставить номер урока
    LaunchedEffect(subjectId, lessonDate) {
        if (sid.isNotBlank()) Sched.lessonsOn(d, ld).firstOrNull { it.subjectId == sid }?.let { lessonNum = it.number }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        busy = true
        scope.launch {
            val errors = mutableListOf<String>()
            for (u in uris) {
                val f = withContext(Dispatchers.IO) { runCatching { Images.importToJpeg(ctx, u, Store.photosDir(), Store.d.photo) } }
                f.onSuccess { photos += it.name; newPhotos += it.name }.onFailure { errors += it.message ?: "ошибка" }
            }
            busy = false
            if (errors.isNotEmpty()) Toast.makeText(ctx, "Фото: ${errors.first()}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(route.key) { if (route.shared.isNotEmpty()) importUris(route.shared) }

    val camFile = remember { File(ctx.cacheDir, "camera").apply { mkdirs() }.let { File(it, "shot.jpg") } }
    var camUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) camUri?.let { importUris(listOf(it)) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { importUris(it) }

    fun buildEntry(): HwEntry {
        val subjName = if (sid.isBlank()) otherName.trim() else d.subject(sid)?.name ?: ""
        val base = existing ?: Entries.create(d, sid, subjName, ld, lessonNum, "", due = due)
        return base.copy(
            subjectId = sid,
            subjectName = subjName,
            text = text.text,
            photos = photos.toList(),
            noHw = noHw,
            lessonDate = ld.toString(),
            lessonNum = lessonNum,
            due = due.toString(),
            status = if (base.status == EntryStatus.FAILED) EntryStatus.QUEUED else base.status,
            error = "",
            // запись изменили — отправить её целиком заново
            photosPosted = emptyList(),
        )
    }

    fun valid(): Boolean {
        if (sid.isBlank() && otherName.isBlank()) { Toast.makeText(ctx, "Выбери предмет", Toast.LENGTH_SHORT).show(); return false }
        if (!noHw && text.text.isBlank() && photos.isEmpty()) { Toast.makeText(ctx, "Напиши ДЗ или добавь фото", Toast.LENGTH_SHORT).show(); return false }
        return true
    }

    fun save(send: Boolean) {
        val e = buildEntry()
        if (com.ilya.dzshka.link.Link.isHelper) {
            // помощник: сразу отправляем основному
            if (existing != null) Store.removeEntry(e.id, false)
            com.ilya.dzshka.work.Entries.addAndMaybeSend(ctx, e.copy(status = EntryStatus.QUEUED))
            newPhotos.clear()
            Toast.makeText(ctx, "Отправляю основному…", Toast.LENGTH_SHORT).show()
            onClose()
            return
        }
        if (existing != null) Store.updateEntry(e.id) { e } else Store.update { it.copy(queue = it.queue + e) }
        if (send) Sender.sendNow(ctx, listOf(e.id))
        newPhotos.clear()
        onClose()
    }

    fun close() {
        // новые фото, которые так и не сохранили — удалить
        newPhotos.forEach { File(Store.photosDir(), it).delete() }
        onClose()
    }

    val immediate = Studio.onAddMoment(d) != null
    // У помощника видно только то, что разрешил основной
    val isHelper = com.ilya.dzshka.link.Link.isHelper
    fun hp(p: HelperPerm) = !isHelper || com.ilya.dzshka.link.HelperLink.can(d, p)
    val canOther = hp(HelperPerm.OTHER_SUBJECT)
    val canAnyDay = hp(HelperPerm.ANY_DAY)
    val canDue = hp(HelperPerm.CHANGE_DUE)
    val canPhotos = hp(HelperPerm.PHOTOS)
    val canNoHw = hp(HelperPerm.NO_HW)
    if (isHelper && !canAnyDay && lessonDate != LocalDate.now().toString()) lessonDate = LocalDate.now().toString()
    if (isHelper && !canDue && dueMode == null) dueMode = d.dueMode

    SubScreen(if (existing != null) "Изменить ДЗ" else "Новое ДЗ", onBack = { close() }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Предмет
            val subjOptions = when {
                canOther -> d.subjects.map { it.id } + OTHER
                else -> Sched.lessonsOn(d, ld).map { it.subjectId }.distinct().ifEmpty { listOf(subjectId) }
            }
            if (!canOther && subjectId !in subjOptions) subjOptions.firstOrNull()?.let { subjectId = it }
            Dropdown("Предмет", subjectId, subjOptions, { id ->
                if (id == OTHER) "Другой (написать)…" else d.subject(id)?.let { listOf(it.emoji, it.name).filter { s -> s.isNotBlank() }.joinToString(" ") } ?: "?"
            }, { subjectId = it })
            if (subjectId == OTHER) TextSetting("Название предмета", otherName, { otherName = it })

            // Сегодняшние уроки — быстрый выбор
            val todays = Sched.lessonsOn(d, ld)
            if (todays.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                todays.forEach { l ->
                    FilterChip(
                        selected = l.subjectId == sid,
                        onClick = { subjectId = l.subjectId; lessonNum = l.number },
                        label = { Text("${l.number}. ${d.subject(l.subjectId)?.let { it.short.ifBlank { it.name } } ?: "?"}") },
                    )
                }
            }

            if (canAnyDay) DateButton(lessonDate, { lessonDate = it }, label = "Когда задали", format = { prettyDate(d, it) })
            else Hint("Задали: ${prettyDate(d, ld)}")
            if (isHelper && Sched.lessonsOn(d, ld).isEmpty() && !canOther) Text("Сегодня уроков нет — присылать нечего.", color = MaterialTheme.colorScheme.error)

            // На какую дату
            if (!canDue) Text("ДЗ на: ${prettyDate(d, due)}", style = MaterialTheme.typography.titleSmall)
            if (canDue) Text("На какое число ДЗ", style = MaterialTheme.typography.titleSmall)
            if (canDue) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DueMode.entries.forEach { m ->
                    val dt = Sched.dueFor(d, m, sid, ld)
                    FilterChip(
                        selected = dueMode == m,
                        onClick = { dueMode = m },
                        label = { Text(when (m) {
                            DueMode.NEXT_LESSON -> "К след. уроку"
                            DueMode.NEXT_SCHOOL_DAY -> "След. учебный день"
                            DueMode.TOMORROW -> "Завтра"
                            DueMode.TODAY -> "Сегодня"
                        } + " · " + prettyDate(d, dt)) },
                    )
                }
            }
            if (canDue) DateButton(
                if (dueMode == null) customDue.ifBlank { due.toString() } else "",
                { customDue = it; dueMode = null },
                label = "Своя дата",
                format = { prettyDate(d, it) },
            )

            if (canNoHw && (d.reminder.noHwEnabled || noHw)) SwitchRow("Нет ДЗ", noHw, { noHw = it }, desc = "Отправится: «${d.templates.noHw}»")
            if (sid.isNotBlank() && d.subject(sid)?.noSend == true) Hint("🚫 По этому предмету стоит «не присылать», но записать ДЗ всё равно можно.")
            if (!noHw) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Что задали") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (d.quickPhrases.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.quickPhrases.forEach { ph ->
                        SuggestionChip(onClick = {
                            val t = text.text
                            val pos = text.selection.start.coerceIn(0, t.length)
                            val needSpace = pos > 0 && !t[pos - 1].isWhitespace()
                            val ins = (if (needSpace) " " else "") + ph + (if (ph.endsWith("№")) "" else " ")
                            val nt = t.substring(0, pos) + ins + t.substring(pos)
                            text = TextFieldValue(nt, TextRange(pos + ins.length))
                        }, label = { Text(ph) })
                    }
                }
            }

            // Фото
            if (canPhotos) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    camFile.delete()
                    runCatching { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", camFile) }
                        .onSuccess { u -> camUri = u; runCatching { camera.launch(u) }.onFailure { Toast.makeText(ctx, "Камера не открылась", Toast.LENGTH_SHORT).show() } }
                        .onFailure { Toast.makeText(ctx, "Камера: ${it.message}", Toast.LENGTH_SHORT).show() }
                }) {
                    Icon(Icons.Default.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Камера")
                }
                OutlinedButton(onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Default.Image, null); Spacer(Modifier.width(6.dp)); Text("Галерея")
                }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            if (photos.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos.toList(), key = { it }) { name ->
                    Box {
                        Thumb(File(Store.photosDir(), name))
                        IconButton(
                            onClick = {
                                photos.remove(name)
                                if (name in newPhotos) { newPhotos.remove(name); File(Store.photosDir(), name).delete() }
                            },
                            modifier = Modifier.align(Alignment.TopEnd).size(30.dp),
                        ) { Icon(Icons.Default.Cancel, "Убрать", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // Предпросмотр
            val helperApp = com.ilya.dzshka.link.Link.isHelper
            if (helperApp) {
                val why = com.ilya.dzshka.link.HelperLink.canSend(d, sid)
                if (why != null) Text("⚠ $why", color = MaterialTheme.colorScheme.error)
                Hint("ДЗ уйдёт ${d.link.mainName.ifBlank { "основному" }}, а он отправит его в канал" + if (d.link.myNeedsApproval) " после проверки." else ".")
            } else Text("Как будет в канале", style = MaterialTheme.typography.titleSmall)
            val previewPosts = remember(d, subjectId, otherName, text.text, noHw, photos.size, due, lessonNum, lessonDate) {
                val m = Studio.nowMoment(d)
                val e = buildEntry()
                // если сейчас в студии сообщения идут списком — для одной записи покажем как будет при отправке сейчас
                Studio.plan(d, m.steps, listOf(e), LocalDate.now(), d.sentOnce).map { Studio.SimEvent("", "", it) }
            }
            val preview = previewPosts.joinToString("\n— — —\n") { ev ->
                when (val p = ev.post) {
                    is Post.Text -> p.text
                    is Post.Photo -> "[фото ${photos.indexOf(p.file) + 1}]" + if (p.caption.isNotBlank()) "\n${p.caption}" else ""
                }
            }
            if (!helperApp) ChannelPreview(previewPosts, showTimes = false)
            if (!immediate && !helperApp) {
                val qm = Studio.queueMoment(d)
                Hint(if (qm.trigger == Trigger.MANUAL) "«В очередь» — уйдёт, когда нажмёшь «Отправить» на главной."
                    else "«В очередь» — уйдёт ${Studio.whenText(qm)} вместе с остальными («${qm.name}»). Выше — как будет, если отправить сейчас.")
            }

            if (!helperApp) TestChannelCheck(toTest) { toTest = it }
            if (!helperApp && toTest && d.testReady) {
                Hint("Уйдёт только в тест-канал. " + if (existing != null) "Сама запись в очереди не изменится." else "В очередь не попадёт.")
                Button(onClick = {
                    if (valid() && !busy) {
                        Sender.sendTest(ctx, listOf(buildEntry()), Studio.nowMoment(d).id)
                        newPhotos.clear() // фото удалятся сами после отправки в тест-канал
                        Toast.makeText(ctx, "🧪 Отправляю в тест-канал", Toast.LENGTH_SHORT).show()
                        onClose()
                    }
                }, Modifier.fillMaxWidth().padding(bottom = 24.dp)) { Text("🧪 Отправить в тест-канал") }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                val sendAction = {
                    if (valid() && !busy) {
                        if (d.send.confirm) confirmText = preview else save(true)
                    }
                }
                val queueAction = { if (valid() && !busy) save(false) }
                if (helperApp) {
                    Button(onClick = queueAction, Modifier.weight(1f)) { Text("Отправить") }
                } else if (immediate) {
                    Button(onClick = sendAction, Modifier.weight(1f)) { Text("Отправить") }
                    OutlinedButton(onClick = queueAction, Modifier.weight(1f)) { Text(if (existing != null) "Сохранить" else "В очередь") }
                } else {
                    Button(onClick = queueAction, Modifier.weight(1f)) { Text(if (existing != null) "Сохранить" else "В очередь") }
                    OutlinedButton(onClick = sendAction, Modifier.weight(1f)) { Text("Отправить сейчас") }
                }
            }
        }
    }

    confirmText?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmText = null },
            title = { Text("Отправить в канал?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { PreviewBox(t) } },
            confirmButton = { TextButton(onClick = { confirmText = null; save(true) }) { Text("Отправить") } },
            dismissButton = { TextButton(onClick = { confirmText = null }) { Text("Отмена") } },
        )
    }
}

@Composable
fun Thumb(f: File, size: Int = 96) {
    val bmp by produceState<ImageBitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= size * 3) s *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.size(size.dp)) {
        bmp?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}
