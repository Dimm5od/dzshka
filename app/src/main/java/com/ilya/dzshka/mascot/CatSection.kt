package com.ilya.dzshka.mascot

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ilya.dzshka.R
import com.ilya.dzshka.data.AppData
import com.ilya.dzshka.data.Store
import com.ilya.dzshka.ui.FlowRowButtons
import com.ilya.dzshka.ui.Hint
import com.ilya.dzshka.ui.Perms
import com.ilya.dzshka.ui.Section
import com.ilya.dzshka.ui.TextSetting
import kotlinx.coroutines.delay

/** Живой кот: моргает, машет хвостом, покачивается. Нажатие — реакция. */
@Composable
fun LiveCat(mood: Mascot.Mood, modifier: Modifier = Modifier, onTap: () -> Unit = {}) {
    var frame by remember { mutableIntStateOf(R.drawable.cat_sit) }
    LaunchedEffect(mood) {
        if (mood != Mascot.Mood.SIT) return@LaunchedEffect
        var i = 0
        while (true) {
            frame = when (i % 12) { 4 -> R.drawable.cat_blink; 8, 9 -> R.drawable.cat_tail; else -> R.drawable.cat_sit }
            i++; delay(380)
        }
    }
    val res = when (mood) {
        Mascot.Mood.HAPPY -> R.drawable.cat_happy
        Mascot.Mood.SLEEP -> R.drawable.cat_sleep
        Mascot.Mood.WOW -> R.drawable.cat_wow
        Mascot.Mood.WAVE -> R.drawable.cat_wave
        Mascot.Mood.SIT -> frame
    }
    val inf = rememberInfiniteTransition(label = "cat")
    val bob by inf.animateFloat(0f, if (mood == Mascot.Mood.SLEEP) 2f else 5f,
        infiniteRepeatable(tween(if (mood == Mascot.Mood.SLEEP) 1800 else 900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val jump = remember { Animatable(0f) }
    LaunchedEffect(mood) { if (mood == Mascot.Mood.HAPPY || mood == Mascot.Mood.WOW) { jump.snapTo(0f); jump.animateTo(-18f, tween(140)); jump.animateTo(0f, spring(0.35f, 400f)) } }
    Image(painterResource(res), "Кот", modifier
        .graphicsLayer { translationY = bob + jump.value }
        .clickable(remember { MutableInteractionSource() }, null, onClick = onTap))
}

@Composable
fun CatSection(d: AppData) {
    val ctx = LocalContext.current
    var line by remember { mutableStateOf(Mascot.daily(d)) }
    var mood by remember { mutableStateOf(if (Mascot.isNight()) Mascot.Mood.SLEEP else Mascot.Mood.SIT) }
    var taps by remember { mutableStateOf(listOf<Long>()) }
    LaunchedEffect(mood) { if (mood != Mascot.Mood.SIT && mood != Mascot.Mood.SLEEP) { delay(2500); mood = Mascot.Mood.SIT } }
    val out = d.mascot.out && CatService.running
    Section("🐈 Кот на экране") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveCat(mood, Modifier.size(110.dp)) {
                val t = System.currentTimeMillis()
                taps = taps.filter { t - it < 8000 } + t
                line = when {
                    Mascot.isNight() -> Mascot.react(Store.d, Phrases.sleepyTap, Mascot.Mood.SLEEP, line.text)
                    taps.size >= 6 -> Mascot.react(Store.d, Phrases.petSpam, Mascot.Mood.WOW, line.text)
                    else -> Mascot.react(Store.d, Phrases.pet, Mascot.Mood.HAPPY, line.text)
                }
                mood = line.mood
            }
            Surface(Modifier.weight(1f).padding(start = 8.dp), shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                Text(line.text, Modifier.padding(12.dp).clickable { line = Mascot.another(Store.d, line.text); mood = line.mood }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Hint("📚 " + Mascot.hwLine(d) + ". Погладь кота — нажми на него, новая реплика — нажми на облачко.")
        TextSetting("Как зовут кота", d.mascot.name, { v -> Store.update { it.copy(mascot = it.mascot.copy(name = v)) } })
        FlowRowButtons {
            Button(onClick = {
                when {
                    out -> CatService.home(ctx)
                    !Perms.canOverlay(ctx) -> { Toast.makeText(ctx, "Разреши «Поверх других приложений» и нажми ещё раз", Toast.LENGTH_LONG).show(); Perms.askOverlay(ctx) }
                    else -> CatService.out(ctx)
                }
            }) { Text(if (out) "🏠 Позвать домой" else "🐾 Выпустить кота") }
        }
        Hint("На экране: нажми — погладить, тащи пальцем — переедет, двойное нажатие — домой.")
    }
}

/** Настройки виджета ДЗ: что показывать. Размер меняется на рабочем столе — от 1 клетки. */
@Composable
fun WidgetSection(d: AppData) {
    val ctx = LocalContext.current
    val w = d.widget
    fun upd(f: (com.ilya.dzshka.data.WidgetCfg) -> com.ilya.dzshka.data.WidgetCfg) = Store.update { it.copy(widget = f(it.widget)) }
    Section("📱 Виджет ДЗ") {
        Hint("Размер любой: 1 клетка — только сколько ДЗ на завтра, полоска — сколько и по каким предметам, побольше — всё ниже. Растягивай прямо на рабочем столе.")
        com.ilya.dzshka.ui.SwitchRow("Реплика (текстом)", w.phrase, { v -> upd { it.copy(phrase = v) } }, desc = "Нажми на неё на виджете — новая")
        com.ilya.dzshka.ui.SwitchRow("ДЗ на завтра — сколько и какое", w.next, { v -> upd { it.copy(next = v) } })
        com.ilya.dzshka.ui.SwitchRow("Что на сегодня", w.dueToday, { v -> upd { it.copy(dueToday = v) } })
        com.ilya.dzshka.ui.SwitchRow("Что сегодня задали", w.givenToday, { v -> upd { it.copy(givenToday = v) } })
        OutlinedButton(onClick = {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ok = runCatching { mgr.isRequestPinAppWidgetSupported && mgr.requestPinAppWidget(ComponentName(ctx, CatWidget::class.java), null, null) }.getOrDefault(false)
            if (!ok) Toast.makeText(ctx, "Добавь вручную: долгое нажатие на рабочем столе → Виджеты → ДЗшка", Toast.LENGTH_LONG).show()
        }) { Text("➕ Виджет на экран") }
    }
}
