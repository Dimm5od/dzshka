package com.ilya.dzshka.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ilya.dzshka.BuildConfig
import com.ilya.dzshka.net.Updater
import com.ilya.dzshka.net.Updater.St

/** Раздел «Обновление»: одна кнопка — проверить, скачать и поставить поверх. */
@Composable
fun UpdateSection() {
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) Updater.resumeIfAllowed(ctx) }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Section("Обновление") {
        Text("Сейчас стоит версия ${BuildConfig.VERSION_NAME}")
        val busy = st is St.Checking || st is St.Downloading || st is St.Installing
        when (val s = st) {
            St.Idle -> {}
            St.Checking -> Hint("Проверяю…")
            is St.UpToDate -> Hint("✅ Это последняя версия")
            is St.NeedPermission -> Hint("Есть ${s.rel.name}. Разреши ДЗшке «Установку приложений» и вернись сюда — обновление продолжится само.")
            is St.Downloading -> {
                Hint("Скачиваю ${s.rel.name}… ${s.pct}%")
                LinearProgressIndicator(progress = { s.pct / 100f }, modifier = Modifier.fillMaxWidth())
            }
            is St.Installing -> Hint("Ставлю ${s.rel.name}… Приложение закроется и обновится.")
            is St.WaitConfirm -> Hint("Телефон спрашивает подтверждение — нажми «Обновить».")
            is St.Error -> Text("✗ ${s.msg}", color = MaterialTheme.colorScheme.error)
        }
        (st as? St.Downloading)?.rel?.notes?.takeIf { it.isNotBlank() }?.let { Hint("Что нового: $it") }
        Button(onClick = { Updater.run(ctx) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Обновляю…" else "⬇ Обновить")
        }
    }
}
