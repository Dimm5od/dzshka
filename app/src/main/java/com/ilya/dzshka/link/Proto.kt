package com.ilya.dzshka.link

import com.ilya.dzshka.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** То, что помощник получает от основного: расписание и всё, что нужно для напоминаний. Без токенов и ключей. */
@Serializable
data class HelperCfg(
    val ver: Long = 0,
    val mainName: String = "",
    val channelName: String = "",
    val subjects: List<Subject> = emptyList(),
    val bells: List<BellProfile> = emptyList(),
    val weeks: List<Week> = emptyList(),
    val weekAnchor: String = "",
    val firstLessonNum: Int = 1,
    val holidays: List<Holiday> = emptyList(),
    val dueMode: DueMode = DueMode.NEXT_LESSON,
    val reminder: ReminderCfg = ReminderCfg(),
    val notifTitle: String = "",
    val notifText: String = "",
    val noHw: String = "",
    val tokens: List<TokenDef> = emptyList(),
    val tokenWholeWord: Boolean = true,
    val tokenCase: Boolean = true,
    val monthsA: List<String> = emptyList(),
    val monthsB: List<String> = emptyList(),
    val weekdays: List<String> = emptyList(),
    val weekdaysShort: List<String> = emptyList(),
    val quickPhrases: List<String> = emptyList(),
    val hosting: Hosting = Hosting.CATBOX,
    val litterTime: String = "72h",
    val maxSide: Int = 1600,
    val quality: Int = 85,
    val maxKb: Int = 950,
)

@Serializable
data class HwPayload(
    val clientId: String,
    val subjectId: String,
    val subjectName: String,
    val text: String,
    val noHw: Boolean,
    val due: String,
    val lessonDate: String,
    val lessonNum: Int,
    val photoUrls: List<String> = emptyList(),
)

@Serializable
enum class AckState(val label: String) {
    DELIVERED("доставлено основному"),
    PENDING("ждёт одобрения"),
    QUEUED("в очереди у основного"),
    SENT("в канале ✓"),
    REJECTED("отклонено"),
}

/** Любое сообщение между основным и помощником. Ненужные поля пустые. */
@Serializable
data class Packet(
    val type: String,
    val from: String,
    val to: String = "",
    val time: Long = System.currentTimeMillis(),
    // hello
    val name: String = "",
    val model: String = "",
    val version: String = "",
    val cfgVer: Long = 0,
    // hw
    val hw: HwPayload? = null,
    // cfg
    val cfg: HelperCfg? = null,
    // perm
    val status: HelperStatus? = null,
    val allowed: List<String> = emptyList(),
    val needsApproval: Boolean = false,
    val perms: List<HelperPerm> = emptyList(),
    // post
    val text: String = "",
    // ack
    val clientId: String = "",
    val ack: AckState? = null,
    val note: String = "",
) {
    companion object {
        const val HELLO = "hello"
        const val HW = "hw"
        const val CFG = "cfg"
        const val PERM = "perm"
        const val ACK = "ack"
        const val BYE = "bye"
        const val SKIP = "skip"
        const val POST = "post"
    }
}

object Proto {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; coerceInputValues = true; explicitNulls = false }

    fun encode(p: Packet): String = json.encodeToString(Packet.serializer(), p)
    fun decode(s: String): Packet? = runCatching { json.decodeFromString(Packet.serializer(), s) }.getOrNull()

    fun upTopic(base: String) = "${base}u"
    fun downTopic(base: String) = "${base}d"

    fun cfgFrom(d: AppData): HelperCfg = HelperCfg(
        mainName = d.link.myName,
        channelName = d.viber.channelName,
        subjects = d.subjects,
        bells = d.bells,
        weeks = d.weeks,
        weekAnchor = d.weekAnchor,
        firstLessonNum = d.firstLessonNum,
        holidays = d.holidays,
        dueMode = d.dueMode,
        reminder = d.reminder,
        notifTitle = d.templates.notifTitle,
        notifText = d.templates.notifText,
        noHw = d.templates.noHw,
        tokens = d.tokens,
        tokenWholeWord = d.tokenWholeWord,
        tokenCase = d.tokenCase,
        monthsA = d.monthsA,
        monthsB = d.monthsB,
        weekdays = d.weekdays,
        weekdaysShort = d.weekdaysShort,
        quickPhrases = d.quickPhrases,
        hosting = if (d.photo.hosting == Hosting.LITTERBOX) Hosting.LITTERBOX else Hosting.CATBOX,
        litterTime = d.photo.litterTime,
        maxSide = d.photo.maxSide,
        quality = d.photo.quality,
        maxKb = d.photo.maxKb,
    ).let { it.copy(ver = json.encodeToString(HelperCfg.serializer(), it).hashCode().toLong() and 0xFFFFFFFFL) }

    /** Помощник применяет присланное расписание к своим данным. */
    fun applyCfg(d: AppData, c: HelperCfg): AppData = d.copy(
        subjects = c.subjects,
        bells = c.bells.ifEmpty { d.bells },
        weeks = c.weeks.ifEmpty { d.weeks },
        weekAnchor = c.weekAnchor,
        firstLessonNum = c.firstLessonNum,
        holidays = c.holidays,
        dueMode = c.dueMode,
        reminder = c.reminder.copy(sound = d.reminder.sound, vibrate = d.reminder.vibrate),
        templates = d.templates.copy(
            notifTitle = c.notifTitle.ifBlank { d.templates.notifTitle },
            notifText = c.notifText.ifBlank { d.templates.notifText },
            noHw = c.noHw.ifBlank { d.templates.noHw },
        ),
        tokens = c.tokens.ifEmpty { d.tokens },
        tokenWholeWord = c.tokenWholeWord,
        tokenCase = c.tokenCase,
        monthsA = c.monthsA.ifEmpty { d.monthsA },
        monthsB = c.monthsB.ifEmpty { d.monthsB },
        weekdays = c.weekdays.ifEmpty { d.weekdays },
        weekdaysShort = c.weekdaysShort.ifEmpty { d.weekdaysShort },
        quickPhrases = c.quickPhrases,
        photo = d.photo.copy(hosting = c.hosting, litterTime = c.litterTime, maxSide = c.maxSide, quality = c.quality, maxKb = c.maxKb),
        viber = d.viber.copy(channelName = c.channelName),
        link = d.link.copy(cfgVer = c.ver, mainName = c.mainName),
        moments = emptyList(),
        setupDone = true,
    )
}
