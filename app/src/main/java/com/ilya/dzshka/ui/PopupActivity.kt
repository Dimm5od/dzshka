package com.ilya.dzshka.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.MainActivity
import com.ilya.dzshka.data.*
import com.ilya.dzshka.work.Alarms
import com.ilya.dzshka.work.Entries
import com.ilya.dzshka.work.Notif
import com.ilya.dzshka.work.TestRun
import java.time.LocalDate

/** Экранчик «Урок закончился» — поверх экрана и на экране блокировки. Вид настраивается в Студии. */
class PopupActivity : ComponentActivity() {
    companion object {
        fun intent(ctx: Context, date: LocalDate, index: Int, number: Int, subjectId: String, nid: Int, test: Boolean = false, testIndex: Int = -1): Intent =
            Intent(ctx, PopupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra("date", date.toString()).putExtra("index", index).putExtra("num", number)
                .putExtra("subject", subjectId).putExtra("nid", nid).putExtra("test", test).putExtra("testIndex", testIndex)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        show(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        show(intent)
    }

    private fun show(i: Intent) {
        val date = i.getStringExtra("date")?.let { Sched.parseDate(it) } ?: LocalDate.now()
        val index = i.getIntExtra("index", 0)
        val num = i.getIntExtra("num", 0)
        val subject = i.getStringExtra("subject") ?: ""
        val nid = i.getIntExtra("nid", 0)
        val test = i.getBooleanExtra("test", false)
        val testIndex = i.getIntExtra("testIndex", -1)
        setContent {
            val d by Store.data.collectAsState()
            if (test) {
                // тестовая неделя ушла дальше — закрываемся
                val st by TestRun.state.collectAsState()
                LaunchedEffect(st.index, st.running, st.waiting) {
                    if (!st.running || st.index != testIndex || !st.waiting) finish()
                }
            }
            DzTheme(d.look) {
                PopupCard(
                    d, d.reminder.screen, date, num, subject, test = test, index = index,
                    onAction = { kind, text ->
                        if (test) {
                            TestRun.answer(when (kind) {
                                NotifButton.REPLY -> TestRun.Answer.Text(text)
                                NotifButton.NO_HW -> TestRun.Answer.NoHw
                                else -> TestRun.Answer.Skip
                            })
                            done(nid); return@PopupCard
                        }
                        when (kind) {
                            NotifButton.REPLY -> {
                                Entries.addAndMaybeSend(this, Entries.create(Store.d, subject, "", date, num, text.trim()))
                                Store.saveNow()
                            }
                            NotifButton.NO_HW -> {
                                Entries.addAndMaybeSend(this, Entries.create(Store.d, subject, "", date, num, "", noHw = true))
                                Store.saveNow()
                            }
                            NotifButton.SKIP -> {
                                if (com.ilya.dzshka.link.Link.isHelper) com.ilya.dzshka.link.HelperLink.sendSkip(this, subject, date)
                                else Entries.skip(subject, date)
                            }
                            NotifButton.SNOOZE -> Alarms.snooze(this, Intent().putExtra("subject", subject).putExtra("date", date.toString())
                                .putExtra("index", index).putExtra("num", num).putExtra("nid", nid), Store.d.reminder.snoozeMin.coerceAtLeast(1))
                        }
                        done(nid)
                    },
                    onOpen = {
                        startActivity(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_ADD)
                            .putExtra("subject", subject).putExtra("date", date.toString()).putExtra("num", num)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        if (Build.VERSION.SDK_INT >= 26) getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
                        done(nid)
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun done(nid: Int) {
        Notif.cancel(this, nid)
        finish()
    }
}

/** Сам экранчик. preview = true — рисуется внутри Студии (без затемнения на весь экран). */
@Composable
fun PopupCard(
    d: AppData, cfg: PopupCfg, date: LocalDate, num: Int, subjectId: String,
    test: Boolean = false, preview: Boolean = false, index: Int = -1,
    onAction: (NotifButton, String) -> Unit = { _, _ -> }, onOpen: () -> Unit = {}, onClose: () -> Unit = {},
) {
    val subj = d.subject(subjectId)
    val c = Notif.reminderCtx(d, date, num, subjectId)
    val label = Builder.r(d, cfg.label, c)
    val title = Builder.r(d, cfg.title, c).ifBlank { subj?.name ?: "Урок" }
    val subtitle = Builder.r(d, cfg.subtitle, c)
    val buttons = Notif.visibleButtons(d, cfg.buttons)
    var writing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(preview) }
    LaunchedEffect(Unit) { visible = true }
    val focus = remember { FocusRequester() }
    val photosAllowed = !com.ilya.dzshka.link.Link.isHelper || com.ilya.dzshka.link.HelperLink.can(d, HelperPerm.PHOTOS)

    val card: @Composable () -> Unit = {
        ElevatedCard(
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 12.dp),
            modifier = Modifier.padding(12.dp).fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cfg.showIcon) {
                        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text(subj?.emoji?.takeIf { it.isNotBlank() } ?: "🔔", style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        if (label.isNotBlank() || test) Text((if (test) "🧪 " else "") + label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Закрыть") }
                }
                val extra = Notif.extraLines(d, date, index, subjectId, Notif.nowMin())
                if (extra.isNotEmpty()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        extra.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (writing) {
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    OutlinedTextField(
                        text, { text = it },
                        label = { Text(Builder.r(d, cfg.hint, c)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onAction(NotifButton.REPLY, text) }),
                        trailingIcon = {
                            IconButton(onClick = { onAction(NotifButton.REPLY, text) }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, cfg.sendLabel) }
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAction(NotifButton.REPLY, text) }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) { Text(cfg.sendLabel) }
                        if (photosAllowed && !test) OutlinedButton(onClick = onOpen) { Text("📷 Фото…") }
                    }
                } else {
                    @Composable
                    fun Btn(b: RemBtn, primary: Boolean, mod: Modifier) {
                        val click = { if (b.kind == NotifButton.REPLY) writing = true else onAction(b.kind, "") }
                        when {
                            primary -> Button(onClick = click, modifier = mod) { Text(b.text, maxLines = 1, style = MaterialTheme.typography.titleMedium) }
                            b.kind == NotifButton.SNOOZE -> OutlinedButton(onClick = click, modifier = mod) { Text(b.text, maxLines = 1) }
                            else -> FilledTonalButton(onClick = click, modifier = mod) { Text(b.text, maxLines = 1) }
                        }
                    }
                    val first = buttons.firstOrNull()
                    if (cfg.bigFirst && first != null) {
                        Btn(first, true, Modifier.fillMaxWidth().height(52.dp))
                        val rest = buttons.drop(1)
                        if (rest.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rest.forEach { Btn(it, false, Modifier.weight(1f)) }
                        }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        buttons.forEachIndexed { i, b -> Btn(b, i == 0, Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    if (preview) { card(); return }

    val align = when (cfg.position) {
        PopupPos.TOP -> Alignment.TopCenter
        PopupPos.CENTER -> Alignment.Center
        PopupPos.BOTTOM -> Alignment.BottomCenter
    }
    Box(
        Modifier.fillMaxSize()
            .background(if (cfg.dim) Color.Black.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClose() }
            .systemBarsPadding().imePadding(),
    ) {
        AnimatedVisibility(
            visible,
            enter = when (cfg.position) {
                PopupPos.TOP -> slideInVertically { -it }
                PopupPos.BOTTOM -> slideInVertically { it }
                PopupPos.CENTER -> fadeIn()
            },
            modifier = Modifier.align(align),
        ) { card() }
    }
}
