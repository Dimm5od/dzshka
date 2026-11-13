package com.ilya.dzshka.net

import android.content.Context
import android.util.Base64
import androidx.work.*
import com.ilya.dzshka.data.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Архив ДЗ на GitHub: один файл hw/archive.json в репозитории.
 * Читать можно без токена (репозиторий публичный), записывать — с токеном (Contents: read & write).
 * Слияние по id («дата|предмет»): побеждает более свежая правка.
 */
object GhSync {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val api get() = System.getProperty("github.api") ?: "https://api.github.com"

    data class Remote(val records: List<HwRecord>, val sha: String?)

    fun schedule(ctx: Context, delaySec: Long = 5) {
        val req = OneTimeWorkRequestBuilder<W>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("gh-sync", ExistingWorkPolicy.REPLACE, req)
    }

    class W(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
        override fun doWork(): Result {
            Store.init(applicationContext)
            return try {
                sync()
                Result.success()
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                Store.update { it.copy(gh = it.gh.copy(lastError = msg)) }
                if (e is IOException && runAttemptCount < 4) Result.retry() else Result.failure()
            }
        }
    }

    private fun url(g: GhCfg) = "$api/repos/${g.owner.trim()}/${g.repo.trim()}/contents/${g.path.trim().trimStart('/')}"

    private fun req(g: GhCfg, u: String): Request.Builder {
        val b = Request.Builder().url(u)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "DZshka")
        if (g.token.isNotBlank()) b.header("Authorization", "Bearer ${g.token.trim()}")
        return b
    }

    fun encode(list: List<HwRecord>): String =
        "[\n" + list.joinToString(",\n") { json.encodeToString(HwRecord.serializer(), it) } + "\n]\n"

    fun decode(text: String): List<HwRecord> =
        if (text.isBlank()) emptyList() else json.decodeFromString(ListSerializer(HwRecord.serializer()), text)

    fun fetch(g: GhCfg): Remote {
        val r = Viber.http.newCall(req(g, url(g) + "?ref=${g.branch.trim()}&t=${System.currentTimeMillis()}").build()).execute()
        r.use {
            val body = it.body?.string().orEmpty()
            if (it.code == 404) return Remote(emptyList(), null)
            if (it.code == 401) throw IllegalStateException("GitHub: токен не подходит (401)")
            if (it.code == 403) throw IOException("GitHub: доступ запрещён или лимит запросов (403). ${body.take(150)}")
            if (!it.isSuccessful) throw IOException("GitHub ответил ${it.code}: ${body.take(150)}")
            val o = Json.parseToJsonElement(body).jsonObject
            val sha = o["sha"]?.jsonPrimitive?.contentOrNull
            val content = o["content"]?.jsonPrimitive?.contentOrNull.orEmpty().replace("\n", "")
            val text = if (content.isBlank()) "" else String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
            return Remote(decode(text), sha)
        }
    }

    fun push(g: GhCfg, list: List<HwRecord>, sha: String?) {
        val body = buildJsonObject {
            put("message", "ДЗ: ${list.size} записей")
            put("content", Base64.encodeToString(encode(list).toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            put("branch", g.branch.trim())
            if (sha != null) put("sha", sha)
        }.toString()
        Viber.http.newCall(req(g, url(g)).put(body.toRequestBody(JSON_TYPE)).build()).execute().use {
            val text = it.body?.string().orEmpty()
            if (it.code == 409 || it.code == 422 && text.contains("sha")) throw Conflict()
            if (it.code == 401) throw IllegalStateException("GitHub: токен не подходит (401)")
            if (it.code == 403 || it.code == 404) throw IllegalStateException("GitHub: у токена нет права записи в ${g.owner}/${g.repo} (${it.code})")
            if (!it.isSuccessful) throw IOException("GitHub не сохранил (${it.code}): ${text.take(150)}")
        }
    }

    private class Conflict : IOException("конфликт версий")

    const val PHOTO_BRANCH = "photos"

    /**
     * Фото для канала — в тот же репозиторий, отдельная ветка photos (чтобы не раздувать main).
     * Ссылка raw.githubusercontent.com не протухает, пока файл лежит в репозитории.
     */
    fun uploadPhoto(g: GhCfg, file: java.io.File): String {
        if (g.token.isBlank()) throw IOException("Для фото на GitHub нужен токен (Настройки → ДЗ на GitHub)")
        val month = java.time.LocalDate.now().toString().take(7)
        val path = "photos/$month/${file.name}"
        val base = "$api/repos/${g.owner.trim()}/${g.repo.trim()}"
        val body = buildJsonObject {
            put("message", "фото $month")
            put("content", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            put("branch", PHOTO_BRANCH)
        }.toString()
        fun put(): Pair<Int, String> = Viber.http.newCall(req(g, "$base/contents/$path").put(body.toRequestBody(JSON_TYPE)).build()).execute()
            .use { it.code to it.body?.string().orEmpty() }
        var (code, text) = put()
        if (code == 404 || code == 422 && text.contains("branch", ignoreCase = true)) {
            // ветки ещё нет — создаём от main
            val ref = Viber.http.newCall(req(g, "$base/git/ref/heads/${g.branch.trim()}").build()).execute().use { it.body?.string().orEmpty() }
            val sha = runCatching { Json.parseToJsonElement(ref).jsonObject["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content }.getOrNull()
                ?: throw IOException("GitHub: не нашёл ветку ${g.branch}")
            val mk = buildJsonObject { put("ref", "refs/heads/$PHOTO_BRANCH"); put("sha", sha) }.toString()
            Viber.http.newCall(req(g, "$base/git/refs").post(mk.toRequestBody(JSON_TYPE)).build()).execute().close()
            put().let { code = it.first; text = it.second }
        }
        if (code == 401) throw IOException("GitHub: токен не подходит (401)")
        if (code == 403 || code == 404) throw IOException("GitHub: у токена нет права записи в ${g.owner}/${g.repo} ($code)")
        if (code !in 200..299) throw IOException("GitHub не сохранил фото ($code): ${text.take(150)}")
        return "https://raw.githubusercontent.com/${g.owner.trim()}/${g.repo.trim()}/$PHOTO_BRANCH/$path"
    }

    /** Забрать с GitHub, слить с телефоном, отправить обратно (если есть токен). @return что сделано */
    fun sync(): String {
        val g = Store.d.gh
        if (!g.enabled) return "выключено"
        repeat(3) {
            val remote = fetch(g)
            val merged = Archive.merge(Store.d.archive, remote.records)
            Store.update { it.copy(archive = Archive.merge(it.archive, merged)) }
            val needPush = g.token.isNotBlank() && encode(merged) != encode(Archive.merge(emptyList(), remote.records))
            if (needPush) {
                try { push(g, merged, remote.sha) } catch (_: Conflict) { return@repeat }
            }
            Store.update { it.copy(gh = it.gh.copy(dirty = false, lastSync = System.currentTimeMillis(), lastError = "")) }
            Store.saveNow()
            return when {
                needPush -> "✓ Сохранено на GitHub (${merged.size})"
                g.token.isBlank() -> "✓ Загружено с GitHub (${remote.records.size}). Чтобы сохранять, вставь токен."
                else -> "✓ Всё совпадает (${merged.size})"
            }
        }
        throw IOException("GitHub: файл всё время меняется, попробую позже")
    }
}
