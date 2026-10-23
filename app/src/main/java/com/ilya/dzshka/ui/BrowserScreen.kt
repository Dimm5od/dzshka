@file:OptIn(ExperimentalMaterial3Api::class)

package com.ilya.dzshka.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ilya.dzshka.Route
import org.json.JSONTokener

/** Поисковики для строки браузера. */
enum class SearchEngine(val label: String, val home: String, private val query: String) {
    GOOGLE("Google", "https://www.google.com/", "https://www.google.com/search?q="),
    DUCK("DuckDuckGo", "https://duckduckgo.com/", "https://duckduckgo.com/?q="),
    BING("Bing", "https://www.bing.com/", "https://www.bing.com/search?q="),
    WIKI("Википедия", "https://ru.wikipedia.org/", "https://ru.wikipedia.org/w/index.php?search=");

    fun search(q: String) = query + Uri.encode(q)
}

/** Что ввели в строку: адрес сайта или поисковый запрос. */
object BrowserInput {
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")
    private val host = Regex("^([\\p{L}0-9-]+\\.)+\\p{L}{2,}(:\\d+)?(/.*)?$")

    fun toUrl(raw: String, engine: SearchEngine): String {
        val s = raw.trim()
        if (s.isEmpty()) return engine.home
        if (s.contains(' ')) return engine.search(s)
        if (scheme.matches(s)) return s
        if (host.matches(s)) return "https://$s"
        return engine.search(s)
    }
}

/** Браузер живёт между заходами: история и открытая страница возвращаются, когда снова открываешь его. */
private object BrowserMem {
    var state: Bundle? = null
}

private const val PREFS = "browser"
private const val KEY_ENGINE = "engine"

private fun loadEngine(ctx: Context): SearchEngine {
    val name = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, null)
    return SearchEngine.entries.firstOrNull { it.name == name } ?: SearchEngine.GOOGLE
}

private fun saveEngine(ctx: Context, e: SearchEngine) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, e.name).apply()
}

private fun openOutside(ctx: Context, uri: Uri) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(ctx, "Нечем открыть", Toast.LENGTH_SHORT).show() }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onBack: () -> Unit, push: (Route) -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var engine by remember { mutableStateOf(loadEngine(ctx)) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf(false) }
    var pageUrl by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }

    val web = remember {
        WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                allowFileAccess = false
                allowContentAccess = false
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(false)
            }
            fun sync(v: WebView) {
                canBack = v.canGoBack()
                canForward = v.canGoForward()
                v.url?.let { pageUrl = it }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    return when (u.scheme?.lowercase()) {
                        "http", "https", "about", "data", "blob" -> false
                        "intent" -> {
                            // ссылки вида intent://… — открываем запасной адрес сайта, если он есть
                            runCatching { Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }
                                .getOrNull()?.takeIf { it.startsWith("http") }?.let { view.loadUrl(it) }
                            true
                        }
                        else -> { openOutside(view.context, u); true } // tel:, mailto:, market: и т. п.
                    }
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    url?.let { pageUrl = it }
                    sync(view)
                }

                override fun onPageFinished(view: WebView, url: String?) = sync(view)
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = sync(view)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
            }
            setDownloadListener { url, _, _, _, _ -> openOutside(context, Uri.parse(url)) }

            val saved = BrowserMem.state
            if (saved == null || restoreState(saved) == null) loadUrl(engine.home)
        }
    }

    DisposableEffect(web) {
        web.onResume()
        onDispose {
            BrowserMem.state = Bundle().also { web.saveState(it) }
            web.stopLoading()
            web.destroy()
        }
    }

    fun hideKeyboard() {
        focus.clearFocus()
        ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(web.windowToken, 0)
    }

    fun go(raw: String) {
        web.loadUrl(BrowserInput.toUrl(raw, engine))
        editing = false
        hideKeyboard()
        web.requestFocus()
    }

    fun toHw() {
        // выделенный текст → в новое ДЗ; ничего не выделено — ссылка на страницу
        web.evaluateJavascript("(function(){return window.getSelection?window.getSelection().toString():''})()") { res ->
            val sel = runCatching { JSONTokener(res).nextValue() as? String }.getOrNull().orEmpty().trim()
            val text = sel.ifEmpty { web.url.orEmpty() }
            if (sel.isEmpty()) Toast.makeText(ctx, "Ничего не выделено — вставил ссылку. Чтобы взять текст, выдели его на странице.", Toast.LENGTH_LONG).show()
            push(Route.Entry(sharedText = text))
        }
    }

    BackHandler {
        when {
            editing -> { editing = false; hideKeyboard() }
            web.canGoBack() -> web.goBack()
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.statusBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.Close, "Закрыть браузер") }
                        OutlinedTextField(
                            value = if (editing) field else TextFieldValue(pageUrl.removePrefix("https://").removePrefix("www.").trimEnd('/')),
                            onValueChange = { field = it },
                            placeholder = { Text("Поиск или адрес") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            trailingIcon = {
                                if (editing && field.text.isNotEmpty()) IconButton(onClick = { field = TextFieldValue("") }) { Icon(Icons.Default.Clear, "Стереть") }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { go(field.text) }),
                            modifier = Modifier.weight(1f).onFocusChanged { f ->
                                if (f.isFocused && !editing) {
                                    editing = true
                                    field = TextFieldValue(pageUrl, TextRange(0, pageUrl.length))
                                }
                            },
                        )
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Скопировать ссылку") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) }, onClick = {
                                    menu = false
                                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(title, web.url.orEmpty()))
                                    Toast.makeText(ctx, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                                })
                                DropdownMenuItem(text = { Text("Поделиться") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                    menu = false
                                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, web.url.orEmpty())
                                    runCatching { ctx.startActivity(Intent.createChooser(send, "Поделиться ссылкой")) }
                                })
                                DropdownMenuItem(text = { Text("Открыть в браузере телефона") }, leadingIcon = { Icon(Icons.Default.OpenInBrowser, null) }, onClick = {
                                    menu = false
                                    web.url?.let { openOutside(ctx, Uri.parse(it)) }
                                })
                                HorizontalDivider()
                                Text("Искать через", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                                SearchEngine.entries.forEach { e ->
                                    DropdownMenuItem(
                                        text = { Text(e.label) },
                                        leadingIcon = { RadioButton(selected = e == engine, onClick = null) },
                                        onClick = { engine = e; saveEngine(ctx, e); menu = false },
                                    )
                                }
                            }
                        }
                    }
                    if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconButton(enabled = canBack, onClick = { web.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                    IconButton(enabled = canForward, onClick = { web.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вперёд") }
                    IconButton(onClick = { if (progress in 1..99) web.stopLoading() else web.reload() }) {
                        if (progress in 1..99) Icon(Icons.Default.Close, "Стоп") else Icon(Icons.Default.Refresh, "Обновить")
                    }
                    IconButton(onClick = { web.loadUrl(engine.home) }) { Icon(Icons.Default.Home, "Домой") }
                    FilledTonalButton(onClick = { toHw() }) {
                        Icon(Icons.Default.EditNote, null)
                        Spacer(Modifier.width(6.dp))
                        Text("В ДЗ")
                    }
                }
            }
        },
    ) { pad ->
        AndroidView(factory = { web }, modifier = Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize())
    }
}
