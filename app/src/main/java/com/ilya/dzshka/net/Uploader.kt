package com.ilya.dzshka.net

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.ilya.dzshka.data.Hosting
import com.ilya.dzshka.data.PhotoCfg
import com.ilya.dzshka.data.newId
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

object Images {
    /** Копирует картинку в папку приложения, поворачивает по EXIF и сжимает в JPEG не больше лимита. */
    fun importToJpeg(ctx: Context, uri: Uri, dir: File, cfg: PhotoCfg): File {
        val cr = ctx.contentResolver
        val rotation = cr.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("Не получилось открыть картинку")
        val maxSide = cfg.maxSide.coerceIn(320, 6000)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Не получилось прочитать картинку")
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f || rotation != 0) {
            val m = Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            if (rotation != 0) m.postRotate(rotation.toFloat())
            val nb = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (nb != bmp) bmp.recycle()
            bmp = nb
        }
        val limit = cfg.maxKb.coerceIn(50, 1000) * 1024
        var q = cfg.quality.coerceIn(30, 100)
        var bytes: ByteArray
        while (true) {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            bytes = bos.toByteArray()
            if (bytes.size <= limit) break
            if (q > 50) q -= 8
            else {
                val nb = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.8f).toInt().coerceAtLeast(1), (bmp.height * 0.8f).toInt().coerceAtLeast(1), true)
                bmp.recycle()
                bmp = nb
            }
        }
        bmp.recycle()
        val out = File(dir, "dz_${System.currentTimeMillis()}_${newId()}.jpeg")
        out.writeBytes(bytes)
        return out
    }

    private fun exifRotation(s: InputStream): Int = runCatching {
        when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}

object Uploader {
    private val JPEG = "image/jpeg".toMediaType()

    fun upload(file: File, cfg: PhotoCfg): String {
        val url = when (cfg.hosting) {
            Hosting.CATBOX -> multipart(
                "https://catbox.moe/user/api.php",
                mapOf("reqtype" to "fileupload") + (if (cfg.catboxUserhash.isNotBlank()) mapOf("userhash" to cfg.catboxUserhash.trim()) else emptyMap()),
                "fileToUpload", file, emptyMap(),
            ).trim()
            Hosting.LITTERBOX -> multipart(
                "https://litterbox.catbox.moe/resources/internals/api.php",
                mapOf("reqtype" to "fileupload", "time" to cfg.litterTime.ifBlank { "72h" }),
                "fileToUpload", file, emptyMap(),
            ).trim()
            Hosting.IMGBB -> {
                if (cfg.imgbbKey.isBlank()) throw IOException("Не указан ключ imgbb (Настройки → Фото)")
                val body = multipart("https://api.imgbb.com/1/upload?key=${cfg.imgbbKey.trim()}", emptyMap(), "image", file, emptyMap())
                jsonPath(body, "data.url") ?: throw IOException("imgbb не вернул ссылку: ${body.take(200)}")
            }
            Hosting.CUSTOM -> {
                if (cfg.customUrl.isBlank()) throw IOException("Не указан адрес своего сервера (Настройки → Фото)")
                val body = multipart(cfg.customUrl.trim(), parseKv(cfg.customExtraFields, '='), cfg.customField.ifBlank { "file" }, file, parseKv(cfg.customHeaders, ':'))
                if (cfg.customJsonPath.isBlank()) body.trim()
                else jsonPath(body, cfg.customJsonPath) ?: throw IOException("Не нашёл ${cfg.customJsonPath} в ответе: ${body.take(200)}")
            }
        }
        if (!url.startsWith("http")) throw IOException("Хостинг ответил не ссылкой: ${url.take(200)}")
        return url
    }

    private fun parseKv(s: String, sep: Char): Map<String, String> = s.lines().mapNotNull {
        val i = it.indexOf(sep)
        if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
    }.toMap()

    private fun multipart(url: String, fields: Map<String, String>, fileField: String, file: File, headers: Map<String, String>): String {
        val b = MultipartBody.Builder().setType(MultipartBody.FORM)
        fields.forEach { (k, v) -> b.addFormDataPart(k, v) }
        b.addFormDataPart(fileField, file.name, file.asRequestBody(JPEG))
        val rb = Request.Builder().url(url).post(b.build()).header("User-Agent", "DZshka/1.0")
        headers.forEach { (k, v) -> rb.header(k, v) }
        Viber.http.newCall(rb.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Хостинг фото: HTTP ${resp.code} ${text.take(200)}")
            return text
        }
    }

    fun jsonPath(body: String, path: String): String? = runCatching {
        var el: JsonElement = Json.parseToJsonElement(body)
        for (p in path.split('.').filter { it.isNotBlank() }) {
            el = if (el is JsonArray) el[p.toInt()] else el.jsonObject[p] ?: return null
        }
        el.jsonPrimitive.contentOrNull
    }.getOrNull()
}
