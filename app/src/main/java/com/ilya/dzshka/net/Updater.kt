package com.ilya.dzshka.net

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.net.Uri
import com.ilya.dzshka.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Обновление одной кнопкой: update.json на GitHub → скачать APK → проверить → поставить поверх.
 * Данные приложения (расписание, настройки) при обновлении поверх не трогаются.
 */
object Updater {
    const val BASE = "https://raw.githubusercontent.com/Dimm5od/dzshka/main/"

    @Serializable
    data class Release(val code: Int, val name: String, val apk: String, val sha256: String, val size: Long = 0, val notes: String = "")

    @Serializable
    data class Manifest(val full: Release? = null, val helper: Release? = null)

    sealed class St {
        data object Idle : St()
        data object Checking : St()
        data class UpToDate(val name: String) : St()
        data class NeedPermission(val rel: Release) : St()
        data class Downloading(val rel: Release, val pct: Int) : St()
        data class Installing(val rel: Release) : St()
        data class WaitConfirm(val rel: Release) : St()
        data class Error(val msg: String) : St()
    }

    private val _state = MutableStateFlow<St>(St.Idle)
    val state: StateFlow<St> get() = _state
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val json = Json { ignoreUnknownKeys = true }

    const val ACTION_STATUS = "com.ilya.dzshka.UPDATE_STATUS"

    val busy get() = _state.value.let { it is St.Checking || it is St.Downloading || it is St.Installing }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    fun askInstallPermission(ctx: Context) = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun pick(m: Manifest): Release? = if (BuildConfig.HELPER) m.helper else m.full

    fun isNewer(r: Release?, current: Int = BuildConfig.VERSION_CODE) = r != null && r.code > current

    /** Та самая одна кнопка: проверить → (разрешение) → скачать → поставить. */
    fun run(ctx: Context) {
        if (job?.isActive == true) return
        val app = ctx.applicationContext
        job = scope.launch {
            try {
                _state.value = St.Checking
                val m = fetchManifest()
                val rel = pick(m)
                if (!isNewer(rel)) { _state.value = St.UpToDate(BuildConfig.VERSION_NAME); return@launch }
                rel!!
                if (!canInstall(app)) {
                    _state.value = St.NeedPermission(rel)
                    withContext(Dispatchers.Main) { askInstallPermission(app) }
                    return@launch
                }
                val f = download(app, rel)
                _state.value = St.Installing(rel)
                install(app, f, rel)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = St.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Вернулись из настроек с разрешением — продолжаем сами, второй раз жать не нужно. */
    fun resumeIfAllowed(ctx: Context) {
        if (_state.value is St.NeedPermission && canInstall(ctx)) run(ctx)
    }

    fun fetchManifest(base: String = BASE): Manifest {
        val req = Request.Builder().url(base + "update.json?t=" + System.currentTimeMillis() / 60_000)
            .header("Cache-Control", "no-cache").build()
        Viber.http.newCall(req).execute().use { r ->
            if (r.code == 404) throw Exception("Обновлений пока нет на сервере")
            if (!r.isSuccessful) throw Exception("Сервер обновлений ответил ${r.code}")
            return json.decodeFromString(Manifest.serializer(), r.body!!.string())
        }
    }

    private fun download(ctx: Context, rel: Release): File {
        val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "update.apk")
        val url = if (rel.apk.startsWith("http")) rel.apk else BASE + rel.apk
        _state.value = St.Downloading(rel, 0)
        Viber.http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Не скачалось: ${r.code}")
            val body = r.body!!
            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastPct = -1
            body.byteStream().use { inp ->
                f.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (pct != lastPct) { lastPct = pct; _state.value = St.Downloading(rel, pct) }
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(rel.sha256, ignoreCase = true)) {
                f.delete()
                throw Exception("Файл скачался с ошибкой (не совпала контрольная сумма). Нажми ещё раз.")
            }
        }
        // это точно наше приложение?
        val info = ctx.packageManager.getPackageArchiveInfo(f.path, 0)
        if (info?.packageName != ctx.packageName) {
            f.delete()
            throw Exception("Скачался не тот файл (${info?.packageName ?: "не APK"})")
        }
        return f
    }

    private fun install(ctx: Context, f: File, rel: Release) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(f.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            f.inputStream().use { inp ->
                s.openWrite("app.apk", 0, f.length()).use { out ->
                    inp.copyTo(out)
                    s.fsync(out)
                }
            }
            val intent = Intent(ACTION_STATUS).setPackage(ctx.packageName)
                .setClass(ctx, UpdateReceiver::class.java)
                .putExtra("name", rel.name)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val p = PendingIntent.getBroadcast(ctx, 4242, intent, flags)
            s.commit(p.intentSender)
        }
    }

    internal fun onStatus(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val rel = (_state.value as? St.Installing)?.rel ?: (_state.value as? St.WaitConfirm)?.rel
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // телефон хочет подтверждения — показываем его окно «Обновить»
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (rel != null) _state.value = St.WaitConfirm(rel)
                confirm?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = St.Idle // обычно сюда не доходим: процесс перезапускается
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                _state.value = St.Error(when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Обновление отменено"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Телефон запретил установку (может, Family Link). $msg".trim()
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт с установленной версией. $msg".trim()
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Версия не подходит к телефону. $msg".trim()
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает памяти на телефоне"
                    else -> "Не установилось. $msg".trim()
                })
            }
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Updater.onStatus(ctx, intent)
}
