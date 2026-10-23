package com.ilya.dzshka.data

import android.content.Context
import com.ilya.dzshka.work.Alarms
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object Store {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        prettyPrint = true
    }

    private lateinit var app: Context
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> get() = _data
    val d: AppData get() = _data.value

    private val io = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null
    private var loaded = false

    private fun file() = File(app.filesDir, "data.json")
    fun photosDir(): File = File(app.filesDir, "photos").apply { mkdirs() }

    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        app = ctx.applicationContext
        val f = file()
        val loadedData = if (f.exists()) runCatching { json.decodeFromString(AppData.serializer(), f.readText()) }
            .getOrElse {
                runCatching { f.copyTo(File(app.filesDir, "data.broken.json"), overwrite = true) }
                AppData()
            } else AppData()
        // Версии до 3: отправка была устроена иначе — ставим стандартный сценарий студии
        var migrated = if (loadedData.version < 3) loadedData.copy(version = 3, moments = Presets.list.first().build()) else loadedData
        // Версия 4: токен «КТО» (кто прислал ДЗ)
        if (migrated.version < 4) migrated = migrated.copy(
            version = 4,
            tokens = if (migrated.tokens.any { it.type == TokenType.AUTHOR }) migrated.tokens
                else migrated.tokens + TokenDef(text = "КТО", type = TokenType.AUTHOR),
        )
        // Версия 5: кнопки уведомления списком; «Нет ДЗ» по умолчанию убрано
        if (migrated.version < 5) migrated = migrated.copy(version = 5, reminder = migrated.reminder.copy(
            buttons = listOf(NotifButton.REPLY, NotifButton.SKIP, NotifButton.SNOOZE)))
        // Версия 6: окно через минуту после урока
        if (migrated.version < 6) migrated = migrated.copy(version = 6, reminder = migrated.reminder.copy(
            offsetMin = if (migrated.reminder.offsetMin == 0) 1 else migrated.reminder.offsetMin, popup = true))
        // Версия 7: вид напоминания (сообщение/экранчик) и настройки кнопок
        if (migrated.version < 7) migrated = migrated.copy(version = 7, reminder = migrated.reminder.copy(
            style = ReminderStyle.POPUP, notifButtons = RemBtn.defaults(), screen = PopupCfg()))
        // Версия 8: архив ДЗ (вкладка «ДЗ») — собрать из уже отправленного
        if (migrated.version < 8) {
            val seed = Archive.seed(migrated)
            migrated = migrated.copy(version = 8, archive = Archive.merge(migrated.archive, seed),
                gh = migrated.gh.copy(dirty = migrated.gh.dirty || seed.isNotEmpty()))
        }
        // Если отправка оборвалась (приложение убили) — вернуть в очередь
        _data.value = migrated.copy(queue = migrated.queue.map {
            if (it.status == EntryStatus.SENDING) it.copy(status = EntryStatus.QUEUED) else it
        })
        loaded = true
    }

    @Synchronized
    fun update(save: Boolean = true, f: (AppData) -> AppData) {
        val nv = f(_data.value)
        if (nv == _data.value) return
        _data.value = nv
        if (save) scheduleSave()
    }

    @Synchronized
    fun replaceAll(nd: AppData) {
        _data.value = nd
        saveNow()
    }

    private fun scheduleSave() {
        pending?.cancel(false)
        pending = io.schedule({ writeFile() }, 350, TimeUnit.MILLISECONDS)
    }

    fun saveNow() {
        pending?.cancel(false)
        writeFile()
    }

    @Synchronized
    private fun writeFile() {
        runCatching {
            val f = file()
            val tmp = File(f.parentFile, "data.json.tmp")
            tmp.writeText(json.encodeToString(AppData.serializer(), _data.value))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
        runCatching { Alarms.reschedule(app) }
        runCatching { com.ilya.dzshka.link.Link.scheduleCfgPublish(app) }
        runCatching { val g = _data.value.gh; if (g.dirty && g.enabled && g.token.isNotBlank()) com.ilya.dzshka.net.GhSync.schedule(app) }
        runCatching { com.ilya.dzshka.mascot.CatWidget.refresh(app) }
    }

    fun exportJson(withToken: Boolean): String {
        val x = d.copy(queue = emptyList(), history = emptyList())
        val y = if (withToken) x else x.copy(viber = x.viber.copy(token = ""), testViber = x.testViber.copy(token = ""),
            gh = x.gh.copy(token = ""), photo = x.photo.copy(imgbbKey = "", catboxUserhash = ""))
        return json.encodeToString(AppData.serializer(), y)
    }

    fun importJson(text: String): Result<Unit> = runCatching {
        val imported = json.decodeFromString(AppData.serializer(), text)
        val cur = d
        replaceAll(
            imported.copy(
                queue = cur.queue,
                history = cur.history,
                viber = if (imported.viber.token.isBlank()) imported.viber.copy(token = cur.viber.token) else imported.viber,
                photo = if (imported.photo.imgbbKey.isBlank()) imported.photo.copy(imgbbKey = cur.photo.imgbbKey) else imported.photo,
            )
        )
    }

    fun addHistory(r: SentRecord) = update { it.copy(history = (listOf(r) + it.history).take(it.historyLimit.coerceAtLeast(0))) }

    fun updateEntry(id: String, f: (HwEntry) -> HwEntry) = update { d ->
        d.copy(queue = d.queue.map { if (it.id == id) f(it) else it })
    }

    fun removeEntry(id: String, deletePhotos: Boolean) {
        val e = d.queue.firstOrNull { it.id == id }
        update { d -> d.copy(queue = d.queue.filterNot { it.id == id }) }
        if (deletePhotos) e?.photos?.forEach { File(photosDir(), it).delete() }
    }
}
