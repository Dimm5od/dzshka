package com.ilya.dzshka.link

import android.content.Context
import android.os.Build
import com.ilya.dzshka.BuildConfig
import com.ilya.dzshka.data.*
import com.ilya.dzshka.work.Entries
import com.ilya.dzshka.work.Notif
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Общее для основного и помощника: отправка пакетов и разбор входящих. */
object Link {
    val isHelper: Boolean get() = BuildConfig.HELPER

    private val io = Executors.newSingleThreadScheduledExecutor()
    private var cfgTask: ScheduledFuture<*>? = null

    fun inTopic(l: LinkCfg) = if (isHelper) Proto.downTopic(l.topic) else Proto.upTopic(l.topic)
    fun outTopic(l: LinkCfg) = if (isHelper) Proto.upTopic(l.topic) else Proto.downTopic(l.topic)

    fun ensureDeviceId() {
        if (Store.d.link.deviceId.isBlank()) Store.update { it.copy(link = it.link.copy(deviceId = LinkCrypto.newId())) }
    }

    /** Отправить пакет (блокирующий вызов, звать не из UI-потока). */
    fun send(p: Packet) {
        val l = Store.d.link
        if (!l.active) return
        Envelope.pack(l.keyB64, Proto.encode(p)).forEach { Ntfy.publish(l.server, outTopic(l), it) }
    }

    fun sendAsync(p: Packet, onError: (Throwable) -> Unit = {}) {
        io.execute { runCatching { send(p) }.onFailure(onError) }
    }

    /** Разобрать пачку сообщений из ntfy. */
    @Synchronized
    fun process(ctx: Context, msgs: List<NtfyMsg>) {
        val l = Store.d.link
        if (!l.active) return
        for (m in msgs) {
            if (m.id in Store.d.link.seen) continue
            val json = Envelope.feed(l.keyB64, m.message)
            Store.update(save = false) { d ->
                val seen = (listOf(m.id) + d.link.seen).take(400)
                d.copy(link = if (isHelper) d.link.copy(lastDownId = m.id, seen = seen) else d.link.copy(lastUpId = m.id, seen = seen))
            }
            val p = json?.let(Proto::decode) ?: continue
            if (p.from == Store.d.link.deviceId) continue
            runCatching { if (isHelper) HelperLink.handle(ctx, p) else MainLink.handle(ctx, p) }
        }
        Store.update { it.copy(link = it.link.copy(lastContact = System.currentTimeMillis())) }
    }

    fun lastId(l: LinkCfg) = if (isHelper) l.lastDownId else l.lastUpId

    /** Один раз забрать новые сообщения. */
    fun pollNow(ctx: Context) {
        val l = Store.d.link
        if (!l.active) return
        val since = lastId(l).ifBlank { "12h" }
        process(ctx, Ntfy.poll(l.server, inTopic(l), since))
    }

    /** Основной: когда меняется расписание — разослать помощникам (с задержкой, чтобы не спамить). */
    fun scheduleCfgPublish(ctx: Context) {
        if (isHelper) return
        val d = Store.d
        if (!d.link.active || d.helpers.none { it.status != HelperStatus.BLOCKED }) return
        cfgTask?.cancel(false)
        cfgTask = io.schedule({ runCatching { MainLink.publishCfg(ctx, force = false) } }, 5, TimeUnit.SECONDS)
    }

    fun deviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}

/** Логика основного приложения. */
object MainLink {

    fun setCode(code: String, myName: String, server: String) {
        val s = LinkCrypto.derive(code)
        Link.ensureDeviceId()
        Store.update { d ->
            val changed = d.link.topic.isNotBlank() && d.link.topic != s.topic
            d.copy(
                link = d.link.copy(
                    keyB64 = s.keyB64, topic = s.topic, myName = myName.trim(), server = server.ifBlank { "https://ntfy.sh" },
                    lastUpId = if (changed) "" else d.link.lastUpId, cfgVer = 0, connectedAt = System.currentTimeMillis(), askLater = false,
                    code = code.trim(),
                ),
                // после смены кода старые помощники отключаются — им надо ввести новый код
                helpers = if (changed) emptyList() else d.helpers,
            )
        }
        Store.saveNow()
    }

    fun handle(ctx: Context, p: Packet) {
        when (p.type) {
            Packet.HELLO -> onHello(ctx, p)
            Packet.HW -> onHw(ctx, p)
            Packet.SKIP -> onSkip(ctx, p)
            Packet.POST -> onPost(ctx, p)
            Packet.BYE -> Store.update { d -> d.copy(helpers = d.helpers.filterNot { it.id == p.from }) }
        }
    }

    private fun onHello(ctx: Context, p: Packet) {
        val d = Store.d
        val existing = d.helpers.firstOrNull { it.id == p.from }
        val h = (existing ?: Helper(
            id = p.from,
            status = if (d.helperDefaults.autoApproveNew) HelperStatus.ACTIVE else HelperStatus.PENDING,
            needsApproval = d.helperDefaults.needsApproval,
            perms = d.helperDefaults.perms,
        )).copy(name = p.name, model = p.model, version = p.version, lastSeen = System.currentTimeMillis(), cfgVer = p.cfgVer)
        Store.update { a -> a.copy(helpers = a.helpers.filterNot { it.id == h.id } + h) }
        if (existing == null) Notif.newHelper(ctx, h)
        sendPerm(h)
        val cfg = Proto.cfgFrom(Store.d)
        if (p.cfgVer != cfg.ver && h.status != HelperStatus.BLOCKED) {
            Link.send(Packet(Packet.CFG, Store.d.link.deviceId, cfg = cfg))
            Store.update { it.copy(link = it.link.copy(cfgVer = cfg.ver)) }
        }
    }

    private fun permPacket(h: Helper) = Packet(Packet.PERM, Store.d.link.deviceId, to = h.id, status = h.status, allowed = h.allowedSubjects,
        needsApproval = h.needsApproval, perms = h.perms, name = Store.d.link.myName)

    fun sendPerm(h: Helper) = Link.send(permPacket(h))

    fun publishCfg(ctx: Context, force: Boolean) {
        val d = Store.d
        if (!d.link.active) return
        val cfg = Proto.cfgFrom(d)
        if (!force && cfg.ver == d.link.cfgVer) return
        Link.send(Packet(Packet.CFG, d.link.deviceId, cfg = cfg))
        Store.update { it.copy(link = it.link.copy(cfgVer = cfg.ver)) }
    }

    private fun ack(to: String, clientId: String, state: AckState, note: String = "") {
        Link.sendAsync(Packet(Packet.ACK, Store.d.link.deviceId, to = to, clientId = clientId, ack = state, note = note))
    }

    private fun onHw(ctx: Context, p: Packet) {
        val hw = p.hw ?: return
        val d = Store.d
        var h = d.helpers.firstOrNull { it.id == p.from }
        if (h == null) {
            // прислал ДЗ, не поздоровавшись (например, основной переустановлен) — считаем новым
            onHello(ctx, p.copy(type = Packet.HELLO))
            h = Store.d.helpers.firstOrNull { it.id == p.from } ?: return
        }
        if (hw.clientId in h.recentClientIds) return // уже обработали (повтор)
        fun touch(f: (Helper) -> Helper) = Store.update { a ->
            a.copy(helpers = a.helpers.map { if (it.id == h.id) f(it).copy(lastSeen = System.currentTimeMillis(), recentClientIds = (listOf(hw.clientId) + it.recentClientIds).take(50)) else it })
        }
        when {
            h.status == HelperStatus.BLOCKED -> { touch { it.copy(rejected = it.rejected + 1) }; ack(h.id, hw.clientId, AckState.REJECTED, "ты заблокирован"); return }
            h.status == HelperStatus.PENDING -> { touch { it.copy(rejected = it.rejected + 1) }; ack(h.id, hw.clientId, AckState.REJECTED, "основной ещё не одобрил тебя"); return }
        }
        val why = checkHw(d, h, hw)
        if (why != null) {
            touch { it.copy(rejected = it.rejected + 1) }
            ack(h.id, hw.clientId, AckState.REJECTED, why); return
        }
        val lessonDate = Sched.parseDate(hw.lessonDate) ?: LocalDate.now()
        val subj = d.subject(hw.subjectId)
        // без права «менять дату сдачи» дата всегда считается по расписанию
        val due = if (h.can(HelperPerm.CHANGE_DUE) || subj == null) hw.due else Sched.dueFor(d, d.dueMode, subj.id, lessonDate).toString()
        val photos = hw.photoUrls.mapIndexed { i, _ -> "remote_${hw.clientId}_$i.jpeg" }
        val e = HwEntry(
            subjectId = subj?.id ?: "",
            subjectName = subj?.name ?: hw.subjectName,
            text = hw.text, noHw = hw.noHw, due = due, lessonDate = hw.lessonDate, lessonNum = hw.lessonNum,
            photos = photos, uploaded = photos.zip(hw.photoUrls).toMap(),
            author = h.shownName, helperId = h.id, clientId = hw.clientId,
            status = if (h.needsApproval) EntryStatus.APPROVAL else EntryStatus.QUEUED,
        )
        touch { it.copy(sent = it.sent + 1) }
        if (h.needsApproval) {
            Store.update { it.copy(queue = it.queue + e) }
            Store.saveNow()
            Notif.helperHw(ctx, e, approval = true)
            ack(h.id, hw.clientId, AckState.PENDING)
        } else {
            Entries.addAndMaybeSend(ctx, e)
            Store.saveNow()
            if (Store.d.helperDefaults.notifyOnHw) Notif.helperHw(ctx, e, approval = false)
            val immediate = Studio.onAddMoment(Store.d) != null
            ack(h.id, hw.clientId, if (immediate) AckState.DELIVERED else AckState.QUEUED)
        }
    }

    /** null — можно; иначе причина отказа. Проверяется у основного, даже если помощник что-то обошёл. */
    fun checkHw(d: AppData, h: Helper, hw: HwPayload, today: LocalDate = LocalDate.now()): String? {
        val subj = d.subject(hw.subjectId)
        val lessonDate = Sched.parseDate(hw.lessonDate)
        val inSchedule = subj != null && lessonDate != null && Sched.lessonsOn(d, lessonDate).any { it.subjectId == subj.id }
        return when {
            h.allowedSubjects.isNotEmpty() && hw.subjectId !in h.allowedSubjects -> "по этому предмету тебе присылать нельзя"
            subj == null && !h.can(HelperPerm.OTHER_SUBJECT) -> "можно только предметы из расписания"
            lessonDate != today && !h.can(HelperPerm.ANY_DAY) -> "можно только за сегодня"
            subj != null && !inSchedule && !h.can(HelperPerm.OTHER_SUBJECT) && !h.can(HelperPerm.ANY_DAY) -> "этого урока сегодня нет в расписании"
            hw.noHw && !h.can(HelperPerm.NO_HW) -> "«нет ДЗ» тебе отмечать нельзя"
            hw.photoUrls.isNotEmpty() && !h.can(HelperPerm.PHOTOS) -> "фото тебе присылать нельзя"
            !hw.noHw && hw.text.isBlank() && hw.photoUrls.isEmpty() -> "пустое ДЗ"
            else -> null
        }
    }

    private fun helperFor(ctx: Context, p: Packet): Helper? {
        Store.d.helpers.firstOrNull { it.id == p.from }?.let { return it }
        onHello(ctx, p.copy(type = Packet.HELLO))
        return Store.d.helpers.firstOrNull { it.id == p.from }
    }

    private fun onSkip(ctx: Context, p: Packet) {
        val hw = p.hw ?: return
        val h = helperFor(ctx, p) ?: return
        if (hw.clientId in h.recentClientIds) return
        Store.update { a -> a.copy(helpers = a.helpers.map { if (it.id == h.id) it.copy(lastSeen = System.currentTimeMillis(), recentClientIds = (listOf(hw.clientId) + it.recentClientIds).take(50)) else it }) }
        val why = when {
            h.status != HelperStatus.ACTIVE -> "основной ещё не одобрил тебя"
            !h.can(HelperPerm.SKIP) -> "«Нету ДЗ» тебе отмечать нельзя"
            Store.d.subject(hw.subjectId) == null -> "нет такого предмета"
            else -> null
        }
        if (why != null) { ack(h.id, hw.clientId, AckState.REJECTED, why); return }
        Entries.skip(hw.subjectId, Sched.parseDate(hw.lessonDate) ?: LocalDate.now())
        ack(h.id, hw.clientId, AckState.SENT, "отмечено «не задали»")
    }

    private fun onPost(ctx: Context, p: Packet) {
        val h = helperFor(ctx, p) ?: return
        if (p.clientId in h.recentClientIds) return
        Store.update { a -> a.copy(helpers = a.helpers.map { if (it.id == h.id) it.copy(lastSeen = System.currentTimeMillis(), recentClientIds = (listOf(p.clientId) + it.recentClientIds).take(50)) else it }) }
        val why = when {
            h.status != HelperStatus.ACTIVE -> "основной ещё не одобрил тебя"
            !h.can(HelperPerm.CHANNEL_POST) -> "писать в канал тебе нельзя"
            p.text.isBlank() -> "пустое сообщение"
            else -> null
        }
        if (why != null) { ack(h.id, p.clientId, AckState.REJECTED, why); return }
        val pp = PendingPost(helperId = h.id, author = h.shownName, clientId = p.clientId, text = p.text.take(5000))
        if (h.needsApproval) {
            Store.update { it.copy(pendingPosts = it.pendingPosts + pp) }
            Store.saveNow()
            Notif.helperPost(ctx, pp)
            ack(h.id, p.clientId, AckState.PENDING)
        } else postNow(pp)
    }

    /** Отправить сообщение помощника в канал (блокирующий вызов). */
    fun postNow(pp: PendingPost) {
        val v = Store.d.viber
        runCatching { com.ilya.dzshka.net.Viber.postText(v.token, v.fromId, pp.text, v.minApi) }
            .onSuccess { tok ->
                com.ilya.dzshka.work.ChannelLog.record(tok, pp.text, "", pp.author)
                Store.update { it.copy(pendingPosts = it.pendingPosts.filterNot { x -> x.id == pp.id }) }
                ack(pp.helperId, pp.clientId, AckState.SENT)
            }
            .onFailure { e ->
                if (Store.d.pendingPosts.none { it.id == pp.id }) Store.update { it.copy(pendingPosts = it.pendingPosts + pp) }
                ack(pp.helperId, pp.clientId, AckState.QUEUED, "не ушло: ${e.message}")
            }
        Store.saveNow()
    }

    fun rejectPost(id: String) {
        val pp = Store.d.pendingPosts.firstOrNull { it.id == id } ?: return
        Store.update { it.copy(pendingPosts = it.pendingPosts.filterNot { x -> x.id == id }) }
        Store.saveNow()
        ack(pp.helperId, pp.clientId, AckState.REJECTED, "основной отклонил")
    }

    fun approve(ctx: Context, entryId: String) {
        val e = Store.d.queue.firstOrNull { it.id == entryId } ?: return
        Store.updateEntry(entryId) { it.copy(status = EntryStatus.QUEUED) }
        com.ilya.dzshka.work.Entries.logHw(e, if (e.noHw) Store.d.templates.noHw else e.text.ifBlank { if (e.photos.isNotEmpty()) "📷 фото" else "" })
        Studio.onAddMoment(Store.d)?.let { com.ilya.dzshka.work.Sender.enqueue(ctx, it.id, listOf(entryId)) }
        Store.saveNow()
        if (e.helperId.isNotBlank()) ack(e.helperId, e.clientId, if (Studio.onAddMoment(Store.d) != null) AckState.DELIVERED else AckState.QUEUED, "одобрено")
    }

    fun reject(ctx: Context, entryId: String, note: String = "") {
        val e = Store.d.queue.firstOrNull { it.id == entryId } ?: return
        Store.removeEntry(entryId, true)
        Store.saveNow()
        if (e.helperId.isNotBlank()) ack(e.helperId, e.clientId, AckState.REJECTED, note.ifBlank { "основной отклонил" })
    }

    /** Вызывается после успешной отправки в канал. */
    fun onSent(entries: List<HwEntry>) {
        entries.filter { it.helperId.isNotBlank() }.forEach { ack(it.helperId, it.clientId, AckState.SENT) }
    }

    fun updateHelper(id: String, f: (Helper) -> Helper) {
        val before = Store.d.helpers.firstOrNull { it.id == id } ?: return
        Store.update { a -> a.copy(helpers = a.helpers.map { if (it.id == id) f(it) else it }) }
        val h = Store.d.helpers.firstOrNull { it.id == id } ?: return
        // помощнику сообщаем только то, что его касается
        if (h.status != before.status || h.allowedSubjects != before.allowedSubjects || h.needsApproval != before.needsApproval || h.perms != before.perms)
            Link.sendAsync(permPacket(h))
    }

    fun removeHelper(id: String) {
        Link.sendAsync(Packet(Packet.PERM, Store.d.link.deviceId, to = id, status = HelperStatus.BLOCKED, name = Store.d.link.myName))
        Store.update { a -> a.copy(helpers = a.helpers.filterNot { it.id == id }) }
    }
}

/** Логика помощника. */
object HelperLink {

    fun connect(code: String, myName: String, server: String) {
        val s = LinkCrypto.derive(code)
        Link.ensureDeviceId()
        Store.update { d ->
            d.copy(link = d.link.copy(
                keyB64 = s.keyB64, topic = s.topic, myName = myName.trim(), server = server.ifBlank { "https://ntfy.sh" },
                lastDownId = "", cfgVer = 0, myStatus = HelperStatus.PENDING, connectedAt = System.currentTimeMillis(),
            ))
        }
        Store.saveNow()
    }

    fun hello() {
        val d = Store.d
        Link.send(Packet(Packet.HELLO, d.link.deviceId, name = d.link.myName, model = Link.deviceModel(),
            version = BuildConfig.VERSION_NAME, cfgVer = d.link.cfgVer))
    }

    fun disconnect() {
        runCatching { Link.send(Packet(Packet.BYE, Store.d.link.deviceId)) }
        Store.update { it.copy(link = LinkCfg(deviceId = it.link.deviceId, myName = it.link.myName, server = it.link.server)) }
        Store.saveNow()
    }

    fun handle(ctx: Context, p: Packet) {
        val me = Store.d.link.deviceId
        when (p.type) {
            Packet.CFG -> p.cfg?.let { c ->
                Store.update { Proto.applyCfg(it, c) }
                Store.saveNow()
            }
            Packet.PERM -> if (p.to == me) {
                val was = Store.d.link.myStatus
                Store.update { it.copy(link = it.link.copy(myStatus = p.status ?: HelperStatus.PENDING, myAllowed = p.allowed,
                    myNeedsApproval = p.needsApproval, myPerms = p.perms, mainName = p.name.ifBlank { it.link.mainName })) }
                if (was != p.status) Notif.helperStatus(ctx, p.status ?: HelperStatus.PENDING)
            }
            Packet.ACK -> if (p.to == me) {
                Store.update { d ->
                    d.copy(outbox = d.outbox.map { if (it.clientId == p.clientId) it.copy(state = p.ack, note = p.note, error = "") else it })
                }
                if (p.ack == AckState.REJECTED) Store.d.outbox.firstOrNull { it.clientId == p.clientId }?.let { Notif.helperRejected(ctx, it) }
            }
        }
    }

    /** Добавить ДЗ к отправке основному. */
    fun submit(ctx: Context, e: HwEntry) {
        val cid = e.clientId.ifBlank { LinkCrypto.newId() }
        val entry = e.copy(clientId = cid, status = EntryStatus.SENDING)
        Store.update { d ->
            d.copy(
                queue = d.queue + entry,
                outbox = (listOf(HelperOut(clientId = cid, subjectName = e.subjectName, text = e.text, noHw = e.noHw, due = e.due, photos = e.photos.size)) + d.outbox).take(100),
            )
        }
        Store.saveNow()
        com.ilya.dzshka.work.HelperSendWorker.enqueue(ctx, entry.id)
    }

    fun can(d: AppData, p: HelperPerm) = p in d.link.myPerms

    /** «Не присылать» от помощника: отметка у себя и у основного. */
    fun sendSkip(ctx: Context, subjectId: String, date: LocalDate) {
        Entries.skip(subjectId, date)
        val cid = LinkCrypto.newId()
        val name = Store.d.subject(subjectId)?.name ?: ""
        Store.update { d -> d.copy(outbox = (listOf(HelperOut(clientId = cid, subjectName = name, text = "не задали", note = "отправляется")) + d.outbox).take(100)) }
        val hw = HwPayload(cid, subjectId, name, "", false, "", date.toString(), 0)
        Link.sendAsync(Packet(Packet.SKIP, Store.d.link.deviceId, hw = hw)) { e ->
            Store.update { d -> d.copy(outbox = d.outbox.map { if (it.clientId == cid) it.copy(error = "нет связи: ${e.message}") else it }) }
        }
    }

    /** Свой текст в канал (если основной разрешил). */
    fun sendPost(text: String) {
        val cid = LinkCrypto.newId()
        Store.update { d -> d.copy(outbox = (listOf(HelperOut(clientId = cid, subjectName = "💬 В канал", text = text, note = "отправляется")) + d.outbox).take(100)) }
        Link.sendAsync(Packet(Packet.POST, Store.d.link.deviceId, clientId = cid, text = text)) { e ->
            Store.update { d -> d.copy(outbox = d.outbox.map { if (it.clientId == cid) it.copy(error = "нет связи: ${e.message}") else it }) }
        }
    }

    fun canSend(d: AppData, subjectId: String): String? = when {
        !d.link.active -> "Не подключено к основному"
        d.link.myStatus == HelperStatus.BLOCKED -> "Основной тебя заблокировал"
        d.link.myStatus == HelperStatus.PENDING -> null // отправить можно, основной решит
        d.link.myAllowed.isNotEmpty() && subjectId !in d.link.myAllowed -> "По этому предмету основной не разрешил присылать"
        else -> null
    }

    @Suppress("unused") private fun today() = LocalDate.now()
}
