package com.ilya.dzshka.work

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ilya.dzshka.data.Store
import java.time.LocalTime

/**
 * «Мои посты»: Viber не присылает уведомлений о своих же постах, поэтому их видно только на экране.
 * Когда открыт экран нашего канала, читаем видимые сообщения за сегодня и отдаём их туда же,
 * куда и сообщения из уведомлений (ChatListener.handle). Другие чаты и приложения не трогаем.
 */
class ChatReader : AccessibilityService() {

    private var lastRun = 0L
    private var lastSig = 0

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() !in ChatListener.VIBER) return
        val now = System.currentTimeMillis()
        if (now - lastRun < 1500) return
        lastRun = now
        runCatching {
            Store.init(applicationContext)
            val d = Store.d
            if (!d.chatHw.enabled || !d.chatHw.readOwn) return
            val channel = d.viber.channelName.trim()
            if (channel.isBlank()) return
            val root = rootInActiveWindow ?: return
            val nodes = mutableListOf<Item>()
            collect(root, nodes, 0)
            val screenH = resources.displayMetrics.heightPixels
            val msgs = pick(nodes, channel, screenH, LocalTime.now()) { seenBefore(it) }
            if (msgs.isEmpty()) return
            val sig = msgs.hashCode()
            if (sig == lastSig) return
            lastSig = sig
            ChatListener.handle(applicationContext, listOf(channel), msgs.map { it to now })
            remember(msgs)
        }
    }

    override fun onInterrupt() {}

    private fun collect(n: AccessibilityNodeInfo, out: MutableList<Item>, depth: Int) {
        if (depth > 40 || out.size > 400) return
        val t = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
        if (t.isNotEmpty() && !n.isEditable) {
            val r = Rect(); n.getBoundsInScreen(r)
            out += Item(t, r.top, r.bottom)
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out, depth + 1) }
    }

    // тексты, которые уже видели раньше (чтобы старые посты без «Сегодня» над ними не считались новыми)
    private fun prefs() = getSharedPreferences("chat_reader", Context.MODE_PRIVATE)
    private fun seenBefore(t: String) = t.hashCode().toString() in (prefs().getStringSet("seen", emptySet()) ?: emptySet())
    private fun remember(ts: List<String>) {
        val old = prefs().getStringSet("seen", emptySet()).orEmpty().toList()
        val all = (ts.map { it.hashCode().toString() } + old).distinct().take(500)
        prefs().edit().putStringSet("seen", all.toSet()).apply()
    }

    data class Item(val text: String, val top: Int, val bottom: Int)

    companion object {
        private val TIME = Regex("""^\d{1,2}:\d{2}$""")
        private val TODAY = setOf("сегодня", "today", "astăzi", "azi", "сьогодні")
        private val OTHER_DAY = Regex(
            """^(вчера|yesterday|ieri|вчора)$|(\d{1,2}\s*(январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|ian|iun|iul|noi|dec))|(^\d{1,2}[./]\d{1,2}([./]\d{2,4})?$)""",
            RegexOption.IGNORE_CASE,
        )

        fun isTodaySep(t: String) = t.lowercase().trim() in TODAY
        fun isOtherDaySep(t: String) = t.length <= 30 && OTHER_DAY.containsMatchIn(t.lowercase().trim())

        /**
         * Какие тексты на экране — сегодняшние посты канала.
         * Экран должен быть каналом (его название вверху). Берём тексты под разделителем «Сегодня»;
         * если разделителя не видно — только незнакомые тексты со временем не раньше 3 часов назад.
         */
        fun pick(items: List<Item>, channel: String, screenH: Int, now: LocalTime, seenBefore: (String) -> Boolean): List<String> {
            val sorted = items.sortedBy { it.top }
            val head = sorted.filter { it.top < screenH / 5 }
            if (head.none { it.text.contains(channel, ignoreCase = true) }) return emptyList()
            val out = mutableListOf<String>()
            var section: Boolean? = null   // true — сегодня, false — другой день, null — не видно
            for ((i, it) in sorted.withIndex()) {
                if (it.top < screenH / 8) continue
                val t = it.text
                when {
                    isTodaySep(t) -> { section = true; continue }
                    isOtherDaySep(t) -> { section = false; continue }
                    TIME.matches(t) || t.length < 4 || t.contains(channel, ignoreCase = true) -> continue
                }
                when (section) {
                    true -> out += t
                    false -> {}
                    null -> {
                        // время под пузырём: ближайшее «чч:мм» ниже текста
                        val time = sorted.drop(i + 1).firstOrNull { o -> TIME.matches(o.text) && o.top - it.bottom < 400 }
                            ?.text?.split(":")?.let { (h, m) -> runCatching { LocalTime.of(h.toInt(), m.toInt()) }.getOrNull() }
                        val fresh = time != null && !time.isAfter(now) && java.time.Duration.between(time, now).toMinutes() <= 180
                        if (fresh && !seenBefore(t)) out += t
                    }
                }
            }
            return out.distinct()
        }

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, ChatReader::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        fun askAccess(ctx: Context) = runCatching {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
