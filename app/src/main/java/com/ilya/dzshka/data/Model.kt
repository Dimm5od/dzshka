package com.ilya.dzshka.data

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().take(8)

@Serializable
data class Subject(
    val id: String = newId(),
    val name: String = "",
    val short: String = "",
    val emoji: String = "",
    val remind: Boolean = true,
    /** «Не присылать» — по этому предмету ДЗ не бывает (физра и т.п.) */
    val noSend: Boolean = false,
)

@Serializable
data class Slot(val start: String = "08:00", val end: String = "08:45")

@Serializable
data class BellProfile(
    val id: String = newId(),
    val name: String = "Обычные звонки",
    val slots: List<Slot> = emptyList(),
)

/** lessons: id предмета по порядку уроков, "" = окно */
@Serializable
data class DaySchedule(
    val enabled: Boolean = true,
    val bellId: String = "",
    val lessons: List<String> = emptyList(),
)

@Serializable
data class Week(
    val name: String = "Неделя",
    val days: List<DaySchedule> = List(7) { DaySchedule(enabled = it < 5) },
)

@Serializable
data class Holiday(
    val id: String = newId(),
    val name: String = "Каникулы",
    val from: String = "",
    val to: String = "",
)

enum class TokenType(val label: String) {
    DAY("Число (5)"),
    DAY2("Число с нулём (05)"),
    MONTH_A("Месяц — список 1"),
    MONTH_B("Месяц — список 2"),
    MONTH_NUM("Номер месяца (1)"),
    MONTH_NUM2("Номер месяца (01)"),
    YEAR("Год (2026)"),
    YEAR2("Год (26)"),
    WEEKDAY("День недели"),
    WEEKDAY_SHORT("День недели кратко"),
    SUBJECT("Предмет"),
    SUBJECT_SHORT("Предмет кратко"),
    SUBJECT_EMOJI("Эмодзи предмета"),
    TEXT("Текст ДЗ"),
    LESSON_NUM("Номер урока"),
    PHOTO_COUNT("Сколько фото"),
    TIME_NOW("Время сейчас (14:05)"),
    AUTHOR("Кто прислал (имя помощника)"),
    CUSTOM("Свой текст"),
    NEWLINE("Перенос строки"),
}

enum class DateSource(val label: String) {
    DUE("Дата, на которую ДЗ"),
    LESSON("День урока, когда задали"),
    TODAY("Сегодня (день отправки)"),
}

@Serializable
data class TokenDef(
    val id: String = newId(),
    val text: String = "",
    val type: TokenType = TokenType.CUSTOM,
    val source: DateSource = DateSource.DUE,
    val value: String = "",
)

@Serializable
data class Templates(
    val noHw: String = "нет ДЗ 🎉",
    val emptyText: String = "см. фото",
    val notifTitle: String = "Урок закончился: П",
    val notifText: String = "Что задали? Нажми, чтобы записать ДЗ на Ч М",
    val tidyUp: Boolean = true,
)

enum class DueMode(val label: String) {
    NEXT_LESSON("К следующему уроку этого предмета"),
    NEXT_SCHOOL_DAY("На следующий учебный день"),
    TOMORROW("На завтра"),
    TODAY("На сегодня"),
}

enum class Hosting(val label: String) {
    CATBOX("catbox.moe — без ключа, хранится всегда"),
    LITTERBOX("litterbox — без ключа, удаляется через 1–72 ч"),
    IMGBB("imgbb.com — нужен бесплатный ключ"),
    CUSTOM("Свой сервер"),
}

enum class ThemeMode(val label: String) { SYSTEM("Как в системе"), LIGHT("Светлая"), DARK("Тёмная") }

@Serializable
data class ViberCfg(
    val token: String = "",
    val fromId: String = "",
    val fromName: String = "",
    val channelName: String = "",
    val webhookUrl: String = "https://httpbin.org/post",
    val minApi: Int = 1,
    val maxTextLen: Int = 7000,
)

@Serializable
data class PhotoCfg(
    val hosting: Hosting = Hosting.CATBOX,
    val imgbbKey: String = "",
    val catboxUserhash: String = "",
    val litterTime: String = "72h",
    val customUrl: String = "",
    val customField: String = "file",
    val customExtraFields: String = "",
    val customHeaders: String = "",
    val customJsonPath: String = "",
    val maxSide: Int = 1600,
    val quality: Int = 85,
    val maxKb: Int = 950,
)

@Serializable
data class ReminderCfg(
    val enabled: Boolean = true,
    val offsetMin: Int = 1,
    val remindLast: Boolean = true,
    val mergeDouble: Boolean = true,
    val snoozeMin: Int = 5,
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val showReply: Boolean = true,
    val showNoHw: Boolean = true,
    val showSkip: Boolean = true,
    val autoCancelMin: Int = 0,
    /** кнопки в уведомлении по порядку (Android показывает максимум 3) */
    val buttons: List<NotifButton> = listOf(NotifButton.REPLY, NotifButton.SKIP, NotifButton.SNOOZE),
    /** устарело, см. style */
    val popup: Boolean = true,
    /** вид напоминания: сообщение (уведомление) или экранчик */
    val style: ReminderStyle = ReminderStyle.POPUP,
    val notifButtons: List<RemBtn> = RemBtn.defaults(),
    val screen: PopupCfg = PopupCfg(),
    /** «Расширенно»: доп. строки о соседних уроках */
    val extended: Boolean = false,
    val extra: ExtraInfo = ExtraInfo(),
) {
    /** «нет ДЗ» на экране записи — только если такая кнопка включена */
    val noHwEnabled: Boolean get() = notifButtons.any { it.kind == NotifButton.NO_HW && it.on } || screen.buttons.any { it.kind == NotifButton.NO_HW && it.on }
}

@Serializable
data class ExtraInfo(
    val passed: Boolean = true,
    val next: Boolean = true,
    val timeLeft: Boolean = true,
    val breakLen: Boolean = true,
    val prev: Boolean = true,
    val prevHw: Boolean = true,
)

enum class ReminderStyle(val label: String, val desc: String) {
    NOTIFICATION("Сообщение", "обычное уведомление в шторке с кнопками"),
    POPUP("Экранчик", "карточка поверх экрана, даже на заблокированном"),
}

/** Кнопка напоминания: что делает, как подписана, показывать ли. */
@Serializable
data class RemBtn(val kind: NotifButton = NotifButton.REPLY, val label: String = "", val on: Boolean = true) {
    val text: String get() = label.ifBlank { kind.label }
    companion object {
        fun defaults() = listOf(
            RemBtn(NotifButton.REPLY, "Написать"),
            RemBtn(NotifButton.SKIP, "Нету ДЗ"),
            RemBtn(NotifButton.SNOOZE, "Напомнить"),
            RemBtn(NotifButton.NO_HW, "Нет ДЗ", on = false),
        )
    }
}

enum class PopupPos(val label: String) { TOP("Сверху"), CENTER("По центру"), BOTTOM("Снизу") }

/** Как выглядит экранчик. Тексты — с токенами (П, Э, У, Ч, М, ДН…). */
@Serializable
data class PopupCfg(
    val position: PopupPos = PopupPos.TOP,
    val label: String = "Урок закончился",
    val title: String = "П",
    val subtitle: String = "У урок · ДЗ на ДН, Ч М",
    val hint: String = "Что задали?",
    val sendLabel: String = "Отправить",
    val showIcon: Boolean = true,
    val dim: Boolean = true,
    val bigFirst: Boolean = true,
    val buttons: List<RemBtn> = RemBtn.defaults(),
)

enum class NotifButton(val label: String, val desc: String) {
    REPLY("Написать", "ввести ДЗ"),
    SKIP("Нету ДЗ", "в канал ничего не уйдёт"),
    SNOOZE("Напомнить", "напомнить ещё раз через несколько минут"),
    NO_HW("Нет ДЗ", "в канал уйдёт текст «нет ДЗ»"),
}

@Serializable
data class SendCfg(
    val confirm: Boolean = true,
    val notifyResult: Boolean = true,
    val deleteSentPhotos: Boolean = true,
)

@Serializable
data class LookCfg(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: Long = 0xFF3F6FD8,
    val dynamic: Boolean = false,
)

enum class EntryStatus { QUEUED, SENDING, FAILED, APPROVAL }

@Serializable
data class HwEntry(
    val id: String = newId(),
    val subjectId: String = "",
    val subjectName: String = "",
    val text: String = "",
    val photos: List<String> = emptyList(),
    val due: String = "",
    val lessonDate: String = "",
    val lessonNum: Int = 0,
    val created: Long = System.currentTimeMillis(),
    val noHw: Boolean = false,
    val status: EntryStatus = EntryStatus.QUEUED,
    val error: String = "",
    val uploaded: Map<String, String> = emptyMap(),
    val textSent: Boolean = false,
    val photosPosted: List<String> = emptyList(),
    /** кто прислал (имя помощника), пусто — сам */
    val author: String = "",
    val helperId: String = "",
    val clientId: String = "",
)

/** Одно ДЗ в архиве. id = "дата урока|предмет" — одинаковый на всех устройствах. */
@Serializable
data class HwRecord(
    val id: String = "",
    val subjectId: String = "",
    val subjectName: String = "",
    val text: String = "",
    /** «не задали» / «нет ДЗ» */
    val none: Boolean = false,
    val lessonDate: String = "",
    val due: String = "",
    val author: String = "",
    /** app, helper, chat */
    val source: String = "app",
    val photos: List<String> = emptyList(),
    val updated: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

@Serializable
data class GhCfg(
    val enabled: Boolean = true,
    val owner: String = "Dimm5od",
    val repo: String = "dzshka",
    val branch: String = "main",
    val path: String = "hw/archive.json",
    val token: String = "",
    val dirty: Boolean = false,
    val lastSync: Long = 0,
    val lastError: String = "",
)

/** Что показывать на виджете (если места хватает). */
@Serializable
data class WidgetCfg(
    val phrase: Boolean = true,
    val dueToday: Boolean = true,
    val givenToday: Boolean = true,
    val next: Boolean = true,
)

@Serializable
data class MascotCfg(
    val name: String = "Барсик",
    /** кот гуляет по экрану */
    val out: Boolean = false,
    /** последнее событие для реплики: hwSent, noHwGiven */
    val event: String = "",
    val eventAt: Long = 0,
)

@Serializable
data class ChatHwCfg(
    val enabled: Boolean = false,
    /** название чата/группы (часть названия); пусто — любой чат Viber */
    val chat: String = "",
    /** какие чаты встречались в уведомлениях — чтобы выбрать из списка */
    val seen: List<String> = emptyList(),
)

@Serializable
data class HwInfo(
    val text: String = "",
    val noHw: Boolean = false,
    val due: String = "",
    val author: String = "",
    val photos: Int = 0,
    val sentAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SentRecord(
    val time: Long = System.currentTimeMillis(),
    val text: String = "",
    val photos: Int = 0,
    val ok: Boolean = true,
    val error: String = "",
)

@Serializable
data class AppData(
    val version: Int = 8,
    val setupDone: Boolean = false,
    val subjects: List<Subject> = emptyList(),
    val bells: List<BellProfile> = listOf(Defaults.bells()),
    val weeks: List<Week> = listOf(Week("Неделя 1")),
    val weekAnchor: String = "",
    val firstLessonNum: Int = 1,
    val holidays: List<Holiday> = emptyList(),
    val tokens: List<TokenDef> = Defaults.tokens(),
    val tokenWholeWord: Boolean = true,
    val tokenCase: Boolean = true,
    val monthsA: List<String> = Defaults.RU_MONTHS_GEN,
    val monthsB: List<String> = Defaults.RU_MONTHS_NOM,
    val weekdays: List<String> = Defaults.RU_WD,
    val weekdaysShort: List<String> = Defaults.RU_WD_SHORT,
    val templates: Templates = Templates(),
    val quickPhrases: List<String> = listOf("№", "стр.", "упр.", "параграф", "выучить", "повторить", "читать", "конспект"),
    val dueMode: DueMode = DueMode.NEXT_LESSON,
    val viber: ViberCfg = ViberCfg(),
    val photo: PhotoCfg = PhotoCfg(),
    val reminder: ReminderCfg = ReminderCfg(),
    val send: SendCfg = SendCfg(),
    val look: LookCfg = LookCfg(),
    val queue: List<HwEntry> = emptyList(),
    val history: List<SentRecord> = emptyList(),
    val historyLimit: Int = 100,
    val sentKeys: List<String> = emptyList(),
    val moments: List<Moment> = Presets.list.first().build(),
    /** что уже отправлено «раз в день»: ключ → дата */
    val sentOnce: Map<String, String> = emptyMap(),
    /** уроки, по которым решили ничего не присылать: "дата|предмет" */
    val skipped: List<String> = emptyList(),
    val channelPosts: List<ChannelPost> = emptyList(),
    val link: LinkCfg = LinkCfg(),
    val helpers: List<Helper> = emptyList(),
    val helperDefaults: HelperDefaults = HelperDefaults(),
    /** у помощника: что отправлено основному и что с этим стало */
    val outbox: List<HelperOut> = emptyList(),
    val pendingPosts: List<PendingPost> = emptyList(),
    /** что записали по уроку: "дата|предмет" → текст (для «Расширенно») */
    val hwLog: Map<String, String> = emptyMap(),
    /** подробности по отправленному ДЗ (для карточки урока): "дата|предмет" → инфо */
    val hwInfo: Map<String, HwInfo> = emptyMap(),
    /** второй, тестовый канал */
    val testViber: ViberCfg = ViberCfg(),
    /** копии записей, которые сейчас уходят в тест-канал (очередь не трогают) */
    val testQueue: List<HwEntry> = emptyList(),
    /** ДЗ, которое увидели в чате Viber (по уведомлениям) */
    val chatHw: ChatHwCfg = ChatHwCfg(),
    /** "дата|предмет" → текст сообщения из чата */
    val fromChat: Map<String, String> = emptyMap(),
    /** всё ДЗ (архив для вкладки «ДЗ»), синхронизируется с GitHub */
    val archive: List<HwRecord> = emptyList(),
    val gh: GhCfg = GhCfg(),
    val mascot: MascotCfg = MascotCfg(),
    val widget: WidgetCfg = WidgetCfg(),
) {
    fun isFromChat(subjectId: String, lessonDate: String) = "$lessonDate|$subjectId" in fromChat
    val testReady get() = testViber.token.isNotBlank() && testViber.fromId.isNotBlank()
    fun isSkipped(subjectId: String, lessonDate: String) = "$lessonDate|$subjectId" in skipped
    fun isSent(subjectId: String, lessonDate: String) = "$lessonDate|$subjectId" in sentKeys
    fun subject(id: String): Subject? = subjects.firstOrNull { it.id == id }
    fun bell(id: String): BellProfile? = bells.firstOrNull { it.id == id } ?: bells.firstOrNull()
}

@Serializable
data class ChannelPost(
    val token: String = "",
    val time: Long = System.currentTimeMillis(),
    val text: String = "",
    val photoUrl: String = "",
    val author: String = "",
    val hidden: Boolean = false,
)

/** Связь основного и помощников. Сам код не хранится — только ключ и имя темы, выведенные из него. */
@Serializable
data class LinkCfg(
    val keyB64: String = "",
    val topic: String = "",
    val server: String = "https://ntfy.sh",
    val deviceId: String = "",
    val myName: String = "",
    val lastUpId: String = "",
    val lastDownId: String = "",
    val cfgVer: Long = 0,
    val listenAlways: Boolean = true,
    val askLater: Boolean = false,
    val connectedAt: Long = 0,
    val lastContact: Long = 0,
    /** для помощника: статус от основного */
    val myStatus: HelperStatus = HelperStatus.PENDING,
    val myAllowed: List<String> = emptyList(),
    val myNeedsApproval: Boolean = false,
    val myPerms: List<HelperPerm> = emptyList(),
    val mainName: String = "",
    /** только у основного: сам код, чтобы его можно было посмотреть */
    val code: String = "",
    /** id уже обработанных сообщений ntfy (сервер может отдать одно и то же повторно) */
    val seen: List<String> = emptyList(),
) {
    val active: Boolean get() = keyB64.isNotBlank() && topic.isNotBlank()
}

/** Что помощнику можно сверх базового «ДЗ текстом по сегодняшнему уроку из расписания». */
enum class HelperPerm(val label: String, val desc: String) {
    PHOTOS("📷 Фото", "прикладывать фото к ДЗ"),
    NO_HW("«Нет ДЗ»", "отмечать, что ДЗ не задали — в канал уйдёт «нет ДЗ»"),
    SKIP("«Нету ДЗ»", "отмечать, что ДЗ не задали — в канал ничего не уйдёт, отметка придёт и тебе"),
    OTHER_SUBJECT("Другой предмет", "присылать ДЗ по предмету, которого сегодня нет в расписании"),
    ANY_DAY("Другой день", "присылать ДЗ за прошлые дни"),
    CHANGE_DUE("Менять дату сдачи", "иначе дата считается сама по расписанию"),
    CHANNEL_POST("💬 Писать в канал", "отправлять в канал свой текст (не ДЗ)"),
}

enum class HelperStatus(val label: String) { PENDING("Ждёт одобрения"), ACTIVE("Может присылать"), BLOCKED("Заблокирован") }

@Serializable
data class Helper(
    val id: String = "",
    val name: String = "",
    val nickname: String = "",
    val model: String = "",
    val version: String = "",
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val status: HelperStatus = HelperStatus.PENDING,
    val needsApproval: Boolean = false,
    /** пусто — все предметы */
    val allowedSubjects: List<String> = emptyList(),
    val sent: Int = 0,
    val rejected: Int = 0,
    val cfgVer: Long = 0,
    val recentClientIds: List<String> = emptyList(),
    val perms: List<HelperPerm> = emptyList(),
) {
    val shownName: String get() = nickname.ifBlank { name.ifBlank { "Помощник" } }
    fun can(p: HelperPerm) = p in perms
}

@Serializable
data class HelperOut(
    val clientId: String = "",
    val subjectName: String = "",
    val text: String = "",
    val noHw: Boolean = false,
    val due: String = "",
    val photos: Int = 0,
    val time: Long = System.currentTimeMillis(),
    val state: com.ilya.dzshka.link.AckState? = null,
    val note: String = "",
    val error: String = "",
)

@Serializable
data class HelperDefaults(
    val autoApproveNew: Boolean = false,
    val needsApproval: Boolean = false,
    val notifyOnHw: Boolean = true,
    val perms: List<HelperPerm> = emptyList(),
)

/** Сообщение в канал от помощника, ждёт проверки. */
@Serializable
data class PendingPost(
    val id: String = newId(),
    val helperId: String = "",
    val author: String = "",
    val clientId: String = "",
    val text: String = "",
    val time: Long = System.currentTimeMillis(),
)

object Defaults {
    val RU_MONTHS_GEN = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    val RU_MONTHS_NOM = listOf("январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь")
    val UK_MONTHS_GEN = listOf("січня", "лютого", "березня", "квітня", "травня", "червня", "липня", "серпня", "вересня", "жовтня", "листопада", "грудня")
    val UK_MONTHS_NOM = listOf("січень", "лютий", "березень", "квітень", "травень", "червень", "липень", "серпень", "вересень", "жовтень", "листопад", "грудень")
    val RO_MONTHS = listOf("ianuarie", "februarie", "martie", "aprilie", "mai", "iunie", "iulie", "august", "septembrie", "octombrie", "noiembrie", "decembrie")
    val EN_MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    val SHORT_RU_MONTHS = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

    val RU_WD = listOf("понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье")
    val RU_WD_ACC = listOf("понедельник", "вторник", "среду", "четверг", "пятницу", "субботу", "воскресенье")
    val RU_WD_SHORT = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    val UK_WD = listOf("понеділок", "вівторок", "середа", "четвер", "пʼятниця", "субота", "неділя")
    val UK_WD_SHORT = listOf("пн", "вт", "ср", "чт", "пт", "сб", "нд")
    val RO_WD = listOf("luni", "marți", "miercuri", "joi", "vineri", "sâmbătă", "duminică")
    val RO_WD_SHORT = listOf("Lu", "Ma", "Mi", "Jo", "Vi", "Sâ", "Du")
    val EN_WD = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val EN_WD_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    val MONTH_PRESETS = listOf(
        "Рус. (января)" to RU_MONTHS_GEN,
        "Рус. (январь)" to RU_MONTHS_NOM,
        "Рус. кратко (янв)" to SHORT_RU_MONTHS,
        "Укр. (січня)" to UK_MONTHS_GEN,
        "Укр. (січень)" to UK_MONTHS_NOM,
        "Рум. (ianuarie)" to RO_MONTHS,
        "Англ. (January)" to EN_MONTHS,
    )
    val WD_PRESETS = listOf(
        "Рус. (среда)" to RU_WD,
        "Рус. (на среду)" to RU_WD_ACC,
        "Рус. кратко" to RU_WD_SHORT,
        "Укр." to UK_WD,
        "Укр. кратко" to UK_WD_SHORT,
        "Рум." to RO_WD,
        "Рум. кратко" to RO_WD_SHORT,
        "Англ." to EN_WD,
        "Англ. кратко" to EN_WD_SHORT,
    )

    fun tokens(): List<TokenDef> = listOf(
        TokenDef(text = "Ч", type = TokenType.DAY),
        TokenDef(text = "ЧЧ", type = TokenType.DAY2),
        TokenDef(text = "М", type = TokenType.MONTH_A),
        TokenDef(text = "МЕС", type = TokenType.MONTH_B),
        TokenDef(text = "ММ", type = TokenType.MONTH_NUM2),
        TokenDef(text = "Г", type = TokenType.YEAR),
        TokenDef(text = "Д", type = TokenType.WEEKDAY),
        TokenDef(text = "ДН", type = TokenType.WEEKDAY_SHORT),
        TokenDef(text = "П", type = TokenType.SUBJECT),
        TokenDef(text = "ПК", type = TokenType.SUBJECT_SHORT),
        TokenDef(text = "Э", type = TokenType.SUBJECT_EMOJI),
        TokenDef(text = "Т", type = TokenType.TEXT),
        TokenDef(text = "У", type = TokenType.LESSON_NUM),
        TokenDef(text = "Ф", type = TokenType.PHOTO_COUNT),
        TokenDef(text = "СЧ", type = TokenType.DAY, source = DateSource.TODAY),
        TokenDef(text = "СМ", type = TokenType.MONTH_A, source = DateSource.TODAY),
        TokenDef(text = "СД", type = TokenType.WEEKDAY, source = DateSource.TODAY),
        TokenDef(text = "ВР", type = TokenType.TIME_NOW),
        TokenDef(text = "КТО", type = TokenType.AUTHOR),
    )

    fun bells(): BellProfile = BellProfile(
        name = "Обычные звонки",
        slots = generate("08:30", 45, listOf(10, 20, 10, 10, 10, 10, 10), 7),
    )

    fun generate(start: String, len: Int, breaks: List<Int>, count: Int): List<Slot> {
        var t = Time.parse(start) ?: 8 * 60 + 30
        val out = mutableListOf<Slot>()
        for (i in 0 until count) {
            val s = t
            val e = t + len
            out += Slot(Time.fmt(s), Time.fmt(e))
            val br = if (breaks.isEmpty()) 10 else breaks.getOrElse(i) { breaks.last() }
            t = e + br
        }
        return out
    }
}

object Time {
    fun parse(s: String): Int? {
        val m = Regex("""^\s*(\d{1,2})[:.](\d{2})\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toInt()
        val mi = m.groupValues[2].toInt()
        if (h > 23 || mi > 59) return null
        return h * 60 + mi
    }

    fun fmt(min: Int): String {
        val m = ((min % 1440) + 1440) % 1440
        return "%02d:%02d".format(m / 60, m % 60)
    }
}
