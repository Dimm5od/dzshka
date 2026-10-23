package com.ilya.dzshka.link

import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Ключ и имя темы, выведенные из кода. Сам код нигде не сохраняется. */
data class LinkSecret(val keyB64: String, val topic: String)

object LinkCrypto {
    private val rnd = SecureRandom()

    fun normalize(code: String) = code.trim().replace(Regex("\\s+"), "").lowercase()

    fun derive(code: String): LinkSecret {
        val c = normalize(code)
        val spec = PBEKeySpec(c.toCharArray(), "dzshka-link-v1".toByteArray(), 60_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val h = MessageDigest.getInstance("SHA-256").digest("dzshka-topic|$c".toByteArray())
        val topic = "dz" + h.take(12).joinToString("") { "%02x".format(it) }
        return LinkSecret(Base64.getEncoder().encodeToString(key), topic)
    }

    fun encrypt(keyB64: String, plain: ByteArray): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(keyB64), "AES"), GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + c.doFinal(gzip(plain)))
    }

    /** null — не наш ключ или испорченное сообщение */
    fun decrypt(keyB64: String, b64: String): ByteArray? = runCatching {
        val all = Base64.getDecoder().decode(b64)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(Base64.getDecoder().decode(keyB64), "AES"), GCMParameterSpec(128, all, 0, 12))
        gunzip(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()

    private fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gunzip(b: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes() }

    fun newId(): String = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
}

/** Одно сообщение из ntfy. */
data class NtfyMsg(val id: String, val time: Long, val message: String)

/** Простой клиент ntfy (ntfy.sh или свой сервер). */
object Ntfy {
    private val TEXT = "text/plain; charset=utf-8".toMediaType()
    const val CHUNK = 3000

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val streamHttp: OkHttpClient = http.newBuilder().readTimeout(100, TimeUnit.SECONDS).build()

    private fun base(server: String) = server.trim().trimEnd('/')

    fun publish(server: String, topic: String, text: String) {
        val req = Request.Builder().url("${base(server)}/$topic")
            .header("Cache", "yes").header("Firebase", "no")
            .post(text.toRequestBody(TEXT)).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Сервер связи: HTTP ${r.code} ${r.body?.string()?.take(150)}")
        }
    }

    private fun parse(line: String): NtfyMsg? = runCatching {
        val o = Json.parseToJsonElement(line).jsonObject
        if (o["event"]?.jsonPrimitive?.content != "message") return null
        NtfyMsg(o["id"]!!.jsonPrimitive.content, o["time"]?.jsonPrimitive?.long ?: 0, o["message"]?.jsonPrimitive?.content ?: "")
    }.getOrNull()

    fun poll(server: String, topic: String, since: String): List<NtfyMsg> {
        val s = since.ifBlank { "all" }
        val req = Request.Builder().url("${base(server)}/$topic/json?poll=1&since=$s").get().build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404 || r.code == 400) {
                // такой id уже удалён из кэша — читаем всё, что есть
                if (s != "all") return poll(server, topic, "all")
            }
            if (!r.isSuccessful) throw IOException("Сервер связи: HTTP ${r.code}")
            return r.body!!.string().lineSequence().filter { it.isNotBlank() }.mapNotNull(::parse).toList()
        }
    }

    /** Держит соединение и вызывает onMsg на каждое сообщение. Возвращается, когда соединение оборвалось. */
    fun stream(server: String, topic: String, since: String, alive: () -> Boolean, onMsg: (NtfyMsg) -> Unit) {
        val req = Request.Builder().url("${base(server)}/$topic/json?since=${since.ifBlank { "all" }}").get().build()
        val call = streamHttp.newCall(req)
        call.execute().use { r ->
            if (!r.isSuccessful) throw IOException("Сервер связи: HTTP ${r.code}")
            val src = r.body!!.source()
            while (alive()) {
                val line = src.readUtf8Line() ?: break
                if (line.isBlank()) continue
                parse(line)?.let(onMsg)
            }
        }
    }
}

/** Конверт: шифрованный JSON, порезанный на куски, чтобы влезть в лимит ntfy. */
object Envelope {
    private const val PREFIX = "DZ1"

    fun pack(keyB64: String, json: String): List<String> {
        val enc = LinkCrypto.encrypt(keyB64, json.toByteArray())
        val parts = enc.chunked(Ntfy.CHUNK)
        val id = LinkCrypto.newId()
        return parts.mapIndexed { i, p -> "$PREFIX|$id|$i|${parts.size}|$p" }
    }

    private val buf = mutableMapOf<String, Array<String?>>()
    private val bufTime = mutableMapOf<String, Long>()

    /** Склеивает куски; возвращает JSON, когда сообщение собрано и расшифровано. */
    @Synchronized
    fun feed(keyB64: String, text: String): String? {
        val p = text.split('|', limit = 5)
        if (p.size != 5 || p[0] != PREFIX) return null
        val id = p[1]
        val idx = p[2].toIntOrNull() ?: return null
        val total = p[3].toIntOrNull() ?: return null
        if (total !in 1..200 || idx !in 0 until total) return null
        val now = System.currentTimeMillis()
        bufTime.entries.removeAll { (k, t) -> (now - t > 30 * 60_000).also { if (it) buf.remove(k) } }
        val arr = buf.getOrPut(id) { arrayOfNulls(total) }
        bufTime[id] = now
        if (arr.size != total) return null
        arr[idx] = p[4]
        if (arr.any { it == null }) return null
        buf.remove(id); bufTime.remove(id)
        return LinkCrypto.decrypt(keyB64, arr.joinToString(""))?.decodeToString()
    }
}
