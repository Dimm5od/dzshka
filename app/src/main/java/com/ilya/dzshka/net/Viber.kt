package com.ilya.dzshka.net

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ViberException(val code: Int, msg: String) : Exception(msg)

data class Member(val id: String, val name: String, val role: String)
data class AccountInfo(
    val name: String,
    val uri: String,
    val members: List<Member>,
    val webhook: String,
    val icon: String = "",
    val background: String = "",
    val subscribers: Int? = null,
    val extra: Map<String, String> = emptyMap(),
)

/** Viber Channels Post API: https://developers.viber.com/docs/tools/channels-post-api/ */
object Viber {
    private val BASE get() = System.getProperty("viber.url") ?: "https://chatapi.viber.com/pa/"
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    fun explain(code: Int, msg: String): String = when (code) {
        1 -> "Неверная ссылка (invalidUrl). Проверь адрес фото или webhook."
        2 -> "Токен канала неверный (invalidAuthToken). Скопируй его заново: канал → Информация → Developer Tools."
        3 -> "Viber не понял запрос (badData): $msg"
        4 -> "Не хватает данных (missingData): $msg. Проверь, что выбран отправитель (ID суперадмина)."
        5, 6 -> "Отправитель не найден в канале. Нажми «Проверить токен» и выбери себя."
        7 -> "Канал заблокирован Viber."
        8 -> "Канал не найден — токен от другого или удалённого канала."
        9 -> "Канал приостановлен Viber."
        10 -> "API канала не активирован (webhookNotSet). Нажми «Активировать API» в настройках Viber."
        12 -> "Слишком много запросов (tooManyRequests). Подожди немного."
        else -> "Ошибка Viber $code: $msg"
    }

    /** «Проверить связь»: есть ли интернет вообще, открывается ли GitHub, отвечает ли сервер Viber. */
    fun diagnose(token: String): String {
        fun ping(name: String, url: String, post: Boolean = false): Pair<Boolean, String> {
            val t0 = System.currentTimeMillis()
            return runCatching {
                val b = Request.Builder().url(url).header("User-Agent", "DZshka")
                if (post) b.header("X-Viber-Auth-Token", token.trim()).post("{}".toRequestBody(JSON_TYPE))
                http.newBuilder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
                    .newCall(b.build()).execute().use { it.code }
                true to "✓ $name — ${System.currentTimeMillis() - t0} мс"
            }.getOrElse { false to "✗ $name — ${it.javaClass.simpleName}: ${it.message?.take(70)}" }
        }
        val net = ping("интернет (google.com)", "https://www.google.com/generate_204")
        val gh = ping("GitHub", "https://api.github.com/")
        val vb = ping("сервер Viber", BASE + "get_account_info", post = true)
        val advice = when {
            !net.first -> "Интернета нет совсем, или ДЗшке он запрещён. Xiaomi: Настройки → Приложения → ДЗшка → «Передача данных» — включи Wi-Fi и мобильный."
            !vb.first -> "Интернет есть, но до сервера Viber не достучаться с этой сети. Переключись на другой интернет (Wi-Fi ↔ мобильный) — ДЗ из очереди уйдёт само."
            else -> "Всё на связи ✓"
        }
        return listOf(net.second, gh.second, vb.second, "", advice).joinToString("\n")
    }

    /** Сетевые ошибки — по-человечески. */
    fun netError(e: Throwable): String? {
        val m = e.message ?: ""
        return when (e) {
            is java.net.UnknownHostException -> "Нет интернета: телефон не нашёл сервер Viber. Проверь Wi-Fi или мобильный интернет."
            is java.net.SocketTimeoutException, is java.net.ConnectException ->
                "Сервер Viber не отвечает с этого интернета (ждал ${if (m.contains("ms")) m.substringAfterLast("after ").trim() else "долго"}). " +
                "Попробуй другой интернет (Wi-Fi ↔ мобильный) или проверь, что ДЗшке разрешён мобильный интернет. ДЗ уйдёт само, когда связь появится."
            is javax.net.ssl.SSLException -> "Связь с Viber оборвалась (${m.take(80)}). Попробуй ещё раз или другой интернет."
            else -> null
        }
    }

    private fun call(token: String, method: String, body: JsonObject): JsonObject {
        val req = Request.Builder()
            .url(BASE + method)
            .header("X-Viber-Auth-Token", token.trim())
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        val r = try { http.newCall(req).execute() } catch (e: IOException) { throw IOException(netError(e) ?: (e.message ?: "нет связи"), e) }
        r.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} от Viber: ${text.take(200)}")
            val obj = runCatching { Json.parseToJsonElement(text).jsonObject }
                .getOrElse { throw IOException("Странный ответ Viber: ${text.take(200)}") }
            val status = obj["status"]?.jsonPrimitive?.intOrNull ?: -1
            if (status != 0) {
                val m = obj["status_message"]?.jsonPrimitive?.contentOrNull ?: ""
                throw ViberException(status, explain(status, m))
            }
            return obj
        }
    }

    fun accountInfo(token: String): AccountInfo {
        val o = call(token, "get_account_info", buildJsonObject { })
        val members = o["members"]?.jsonArray?.mapNotNull { m ->
            val mo = m.jsonObject
            Member(
                id = mo["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                name = mo["name"]?.jsonPrimitive?.contentOrNull ?: "",
                role = mo["role"]?.jsonPrimitive?.contentOrNull ?: "",
            )
        } ?: emptyList()
        return AccountInfo(
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: o["chat_hostname"]?.jsonPrimitive?.contentOrNull ?: "",
            uri = o["uri"]?.jsonPrimitive?.contentOrNull ?: "",
            members = members,
            webhook = o["webhook"]?.jsonPrimitive?.contentOrNull ?: "",
            icon = o["icon"]?.jsonPrimitive?.contentOrNull ?: "",
            background = o["background"]?.jsonPrimitive?.contentOrNull ?: "",
            subscribers = (o["subscribers_count"] ?: o["subscribersCount"] ?: o["members_count"])?.jsonPrimitive?.intOrNull,
            extra = o.filterKeys { it !in setOf("status", "status_message", "members", "name", "uri", "webhook", "icon", "background", "id", "chat_hostname") }
                .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap(),
        )
    }

    fun setWebhook(token: String, url: String) {
        call(token, "set_webhook", buildJsonObject { put("url", url.trim()) })
    }

    /** @return message_token (если Viber его вернул) */
    fun postText(token: String, from: String, text: String, minApi: Int): String {
        return tokenOf(call(token, "post", buildJsonObject {
            put("from", from)
            put("type", "text")
            put("text", text)
            if (minApi > 1) put("min_api_version", minApi)
        }))
    }

    fun postPicture(token: String, from: String, url: String, caption: String, minApi: Int): String {
        return tokenOf(call(token, "post", buildJsonObject {
            put("from", from)
            put("type", "picture")
            put("text", caption.take(768))
            put("media", url)
            if (minApi > 1) put("min_api_version", minApi)
        }))
    }

    private fun tokenOf(o: JsonObject): String = o["message_token"]?.jsonPrimitive?.contentOrNull ?: ""
}
