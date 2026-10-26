package com.ilya.dzshka.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Поисковики. home — стартовая страница, q — адрес поиска. */
enum class SearchEngine(val title: String, val emoji: String, val home: String, val q: String) {
    GOOGLE("Google", "🔎", "https://www.google.com/", "https://www.google.com/search?q="),
    WIKI("Википедия", "📚", "https://ru.wikipedia.org/", "https://ru.wikipedia.org/w/index.php?search="),
    DDG("DuckDuckGo", "🦆", "https://duckduckgo.com/", "https://duckduckgo.com/?q="),
    BING("Bing", "🅱", "https://www.bing.com/", "https://www.bing.com/search?q="),
}

/** Что ввели в строку: адрес сайта открываем, слова — ищем. */
object BrowserInput {
    fun toUrl(input: String, e: SearchEngine): String {
        val q = input.trim()
        return when {
            q.isEmpty() -> e.home
            q.startsWith("http://") || q.startsWith("https://") -> q
            q.contains('.') && !q.contains(' ') -> "https://$q"
            else -> e.q + android.net.Uri.encode(q)
        }
    }
}

private val ENGINES = SearchEngine.entries

private data class Quick(val label: String, val emoji: String, val url: String)

private val QUICK = listOf(
    Quick("Википедия", "📚", "https://ru.wikipedia.org/"),
    Quick("Переводчик", "🌐", "https://translate.google.com/"),
    Quick("Калькулятор", "🧮", "https://www.google.com/search?q=calculator"),
    Quick("Карты", "🗺", "https://www.google.com/maps"),
    Quick("Словарь", "📖", "https://gramota.ru/"),
    Quick("YouTube", "▶", "https://m.youtube.com/"),
)

/** Быстрый браузер для поиска: своя стартовая страница (открывается мгновенно), строка поиска, полоска загрузки. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onBack: (() -> Unit)? = null, push: ((com.ilya.dzshka.Route) -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    var engine by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var onStart by remember { mutableStateOf(true) }   // стартовая страница вместо сайта
    var progress by remember { mutableIntStateOf(100) }
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var canBack by remember { mutableStateOf(false) }
    var canFwd by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val fr = remember { FocusRequester() }

    fun open(input: String) {
        if (input.isBlank()) return
        onStart = false
        web?.loadUrl(BrowserInput.toUrl(input, ENGINES[engine]))
        focus.clearFocus()
    }

    /** «В ДЗ»: выделенный на странице текст (или название и ссылка) — в новую запись ДЗ. */
    fun toHw() {
        val w = web ?: return
        w.evaluateJavascript("(function(){return window.getSelection?window.getSelection().toString():''})()") { res ->
            val sel = runCatching { org.json.JSONTokener(res).nextValue() as? String }.getOrNull().orEmpty().trim()
            val text = sel.ifBlank { listOf(title, url).filter { it.isNotBlank() }.joinToString("\n") }
            push?.invoke(com.ilya.dzshka.Route.Entry(sharedText = text))
        }
    }

    BackHandler(enabled = !onStart || onBack != null) {
        when {
            !onStart && canBack -> web?.goBack()
            !onStart -> { onStart = true; text = "" }
            else -> onBack?.invoke()
        }
    }
    DisposableEffect(Unit) { onDispose { web?.apply { stopLoading(); destroy() }; web = null } }

    Scaffold(topBar = {
        Column(Modifier.background(cs.surface).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Закрыть") }
                // строка поиска-пилюля (на стартовой странице она своя, большая)
                if (onStart) Spacer(Modifier.weight(1f).height(46.dp)) else Row(
                    Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(23.dp)).background(cs.surfaceContainerHighest)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (onStart || editing) ENGINES[engine].emoji else "🔒", Modifier.padding(end = 8.dp))
                    BasicTextField(
                        value = if (editing || onStart) text else (title.ifBlank { url }),
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                        cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { open(text) }),
                        modifier = Modifier.weight(1f).focusRequester(fr).onFocusChanged {
                            if (it.isFocused && !editing) { editing = true; if (!onStart) text = url }
                            if (!it.isFocused) editing = false
                        },
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if ((editing || onStart) && text.isEmpty()) Text("Найти или ввести адрес", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                                inner()
                            }
                        },
                    )
                    if (editing && text.isNotEmpty()) Icon(Icons.Default.Close, "Очистить", Modifier.size(20.dp).clickable { text = "" }, tint = cs.onSurfaceVariant)
                }
                if (!onStart) IconButton(onClick = { if (progress < 100) web?.stopLoading() else web?.reload() }) {
                    Icon(if (progress < 100) Icons.Default.Close else Icons.Default.Refresh, if (progress < 100) "Стоп" else "Обновить")
                }
            }
            // полоска загрузки
            AnimatedVisibility(!onStart && progress < 100) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
    }, bottomBar = {
        if (!onStart) Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(52.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { web?.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                IconButton(onClick = { web?.goForward() }, enabled = canFwd) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вперёд") }
                IconButton(onClick = { onStart = true; text = ""; web?.stopLoading() }) { Icon(Icons.Default.Home, "Начало") }
                if (push != null) TextButton(onClick = { toHw() }) { Icon(Icons.Default.NoteAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("В ДЗ") }
                IconButton(onClick = {
                    runCatching { web?.context?.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }, enabled = url.startsWith("http")) { Icon(Icons.Default.OpenInBrowser, "Открыть в браузере") }
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            // WebView создаётся один раз и живёт, пока открыт экран — без перезагрузок
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Важно: строго «на весь экран». Без этого Compose отдаёт WebView размер «по содержимому»,
                        // и страницы с высотой 100% / 100vh (например, Google SafeSearch) схлопываются в одну строку.
                        layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            cacheMode = WebSettings.LOAD_DEFAULT
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            allowFileAccess = false
                            allowContentAccess = false
                            mediaPlaybackRequiresUserGesture = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            offscreenPreRaster = true
                            javaScriptCanOpenWindowsAutomatically = false
                        }
                        setBackgroundColor(android.graphics.Color.WHITE)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                                val s = r.url.scheme ?: return true
                                if (s == "http" || s == "https") return false
                                // intent:, tel:, mailto: — открыть в системе
                                runCatching { v.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, r.url).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                return true
                            }
                            override fun onPageStarted(v: WebView, u: String?, f: Bitmap?) { url = u ?: url }
                            override fun doUpdateVisitedHistory(v: WebView, u: String?, reload: Boolean) {
                                url = u ?: url; canBack = v.canGoBack(); canFwd = v.canGoForward()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(v: WebView, p: Int) { progress = p }
                            override fun onReceivedTitle(v: WebView, t: String?) { title = t ?: "" }
                        }
                        web = this
                    }
                },
            )
            // стартовая страница — без сети, открывается сразу
            AnimatedVisibility(onStart, enter = fadeIn(), exit = fadeOut()) {
                StartPage(engine, { engine = it }, onSearch = { open(it) }, onQuick = { onStart = false; web?.loadUrl(it) })
            }
        }
    }
}

@Composable
private fun StartPage(engine: Int, setEngine: (Int) -> Unit, onSearch: (String) -> Unit, onQuick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.primaryContainer.copy(alpha = 0.55f), cs.surface)))
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Поиск", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = cs.primary)
        Text("выдели текст на странице и нажми «В ДЗ»", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(cs.surface).padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = q, onValueChange = { q = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                cursorBrush = SolidColor(cs.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(q) }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Например: правописание -тся и -ться", color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } },
            )
            FilledIconButton(onClick = { onSearch(q) }) { Icon(Icons.Default.Search, "Искать") }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ENGINES.forEachIndexed { i, e ->
                FilterChip(selected = engine == i, onClick = { setEngine(i) }, label = { Text("${e.emoji} ${e.title}", maxLines = 1) })
            }
        }
        Spacer(Modifier.height(28.dp))
        // быстрые ссылки плиткой
        QUICK.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { onQuick(k.url) }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
                            Text(k.emoji, style = MaterialTheme.typography.titleLarge)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(k.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
