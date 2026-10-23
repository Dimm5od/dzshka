@file:OptIn(ExperimentalMaterial3Api::class)

package com.ilya.dzshka

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import com.ilya.dzshka.data.Store
import com.ilya.dzshka.ui.*

sealed interface Route {
    data object Home : Route
    data object Schedule : Route
    data object Templates : Route
    data object Studio : Route
    data object Settings : Route
    data class Entry(
        val editId: String? = null,
        val subjectId: String? = null,
        val date: String? = null,
        val num: Int = 0,
        val shared: List<Uri> = emptyList(),
        val sharedText: String = "",
        val key: Long = System.nanoTime(),
    ) : Route
    data object Wizard : Route
    data object History : Route
    data object Subjects : Route
    data object Bells : Route
    data object Import : Route
    data object Holidays : Route
    data object Test : Route
    data object Helpers : Route
    data object Channel : Route
    data object LinkSetup : Route
    data object Hw : Route
    data object Browser : Route
    data class HwFor(val subjectId: String) : Route
}

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_ADD = "com.ilya.dzshka.ADD"
        const val ACTION_TEST = "com.ilya.dzshka.TEST"
        const val ACTION_HELPERS = "com.ilya.dzshka.HELPERS"
        const val ACTION_HW = "com.ilya.dzshka.HW"
    }

    private val incoming = mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Store.init(this)
        incoming.value = routeFrom(intent)
        setContent {
            val d by Store.data.collectAsState()
            DzTheme(d.look, space = true) {
                Box(Modifier.fillMaxSize()) {
                    // ночная тема — космос за всем приложением
                    if (isDark(d.look)) SpaceBackdrop()
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AppRoot(incoming)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeFrom(intent)?.let { incoming.value = it }
    }

    override fun onResume() {
        super.onResume()
        App.visible = true
    }

    override fun onPause() {
        super.onPause()
        App.visible = false
        Store.saveNow()
    }

    private fun routeFrom(i: Intent?): Route? {
        i ?: return null
        return when (i.action) {
            ACTION_TEST -> Route.Test
            ACTION_HELPERS -> Route.Helpers
            ACTION_HW -> Route.HwFor(i.getStringExtra("subject") ?: "")
            ACTION_ADD -> Route.Entry(subjectId = i.getStringExtra("subject"), date = i.getStringExtra("date"), num = i.getIntExtra("num", 0))
            Intent.ACTION_SEND -> {
                val u = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                Route.Entry(shared = listOfNotNull(u), sharedText = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list = IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java) ?: arrayListOf()
                Route.Entry(shared = list, sharedText = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
            }
            else -> null
        }
    }
}

@Composable
fun AppRoot(incoming: MutableState<Route?>) {
    val d by Store.data.collectAsState()
    val helper = BuildConfig.HELPER
    var tab by remember { mutableStateOf<Route>(Route.Home) }
    var stack by remember {
        mutableStateOf(
            when {
                helper -> emptyList()
                !d.setupDone -> listOf<Route>(Route.Wizard)
                !d.link.active && !d.link.askLater -> listOf<Route>(Route.LinkSetup)
                else -> emptyList()
            }
        )
    }

    LaunchedEffect(incoming.value) {
        incoming.value?.let {
            stack = stack.filterNot { r -> r is Route.Entry || r == Route.Test } + it
            incoming.value = null
        }
    }

    val push: (Route) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.isNotEmpty()) { pop() }
    BackHandler(enabled = stack.isEmpty() && tab != Route.Home) { tab = Route.Home }

    // Помощник без подключения — только экран подключения
    if (helper && !d.link.active) {
        HelperConnectScreen()
        return
    }

    val top = stack.lastOrNull()
    if (top != null) {
        when (top) {
            is Route.Entry -> EntryScreen(top, onClose = pop)
            Route.Wizard -> WizardScreen(onDone = {
                stack = if (!Store.d.link.active && !Store.d.link.askLater) listOf(Route.LinkSetup) else emptyList()
                tab = Route.Home
            })
            Route.History -> HistoryScreen(onBack = pop)
            Route.Subjects -> SubjectsScreen(onBack = pop)
            Route.Bells -> BellsScreen(onBack = pop)
            Route.Import -> ImportScreen(onBack = pop)
            Route.Holidays -> HolidaysScreen(onBack = pop)
            Route.Test -> TestScreen(onBack = pop)
            Route.Helpers -> if (helper) pop() else HelpersScreen(onBack = pop, push = push)
            Route.LinkSetup -> if (helper) pop() else LinkSetupScreen(onDone = pop)
            Route.Templates -> TemplatesScreen(onBack = pop)
            is Route.HwFor -> HwScreen(initialSubject = top.subjectId, onBack = pop)
            Route.Channel -> ChannelScreen(push, onBack = pop)
            Route.Browser -> BrowserScreen(onBack = pop, push = push)
            else -> pop()
        }
        return
    }

    if (helper) {
        val tabs = listOf(
            Triple(Route.Home, Icons.Default.Home, "Главная"),
            Triple(Route.Schedule, Icons.Default.CalendarMonth, "Расписание"),
            Triple(Route.Settings, Icons.Default.Settings, "Настройки"),
        )
        Scaffold(bottomBar = {
            NavigationBar {
                tabs.forEach { (r, icon, label) ->
                    NavigationBarItem(selected = tab == r, onClick = { tab = r }, icon = { Icon(icon, null) }, label = { Text(label, maxLines = 1) })
                }
            }
        }) { pad ->
            Box(Modifier.padding(pad).consumeWindowInsets(pad)) {
                when (tab) {
                    Route.Home -> HelperHomeScreen(push)
                    Route.Schedule -> HelperScheduleScreen()
                    Route.Settings -> HelperSettingsScreen(push)
                    else -> {}
                }
            }
        }
        return
    }

    MainTabs(tab, { tab = it }, push)
}

private val MAIN_TABS = listOf(
    NavItem("home", Icons.Default.Home, "Главная"),
    NavItem("schedule", Icons.Default.Schedule, "Расписание"),
    NavItem("hw", Icons.Default.MenuBook, "ДЗ"),
    NavItem("settings", Icons.Default.Settings, "Настройки"),
)
private val STUDIO_TABS = listOf(
    NavItem("send", Icons.AutoMirrored.Filled.Send, "Отправка"),
    NavItem("remind", Icons.Default.NotificationsActive, "Напоминания"),
    NavItem("texts", Icons.Default.TextFields, "Тексты"),
    NavItem("test", Icons.Default.Science, "Тест"),
)

/** Основные вкладки + режим Студии (вкладки меняются через «чёрную дыру» по центру). */
@Composable
private fun MainTabs(tab: Route, setTab: (Route) -> Unit, push: (Route) -> Unit) {
    var studio by rememberSaveable { mutableStateOf(false) }
    var studioTab by rememberSaveable { mutableStateOf("send") }
    val pull = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val mainKey = when (tab) { Route.Schedule -> "schedule"; Route.Hw -> "hw"; Route.Settings -> "settings"; else -> "home" }

    fun toggleStudio() {
        if (pull.isRunning) return
        scope.launch {
            pull.animateTo(1f, androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutLinearInEasing))
            studio = !studio
            pull.animateTo(0f, androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }
    }
    BackHandler(enabled = studio) { toggleStudio() }

    val items = if (studio) STUDIO_TABS else MAIN_TABS
    val current = if (studio) studioTab else mainKey
    Scaffold(bottomBar = {
        DzBottomBar(items, current, studio, pull.value,
            onSelect = { k ->
                if (studio) studioTab = k
                else setTab(when (k) { "schedule" -> Route.Schedule; "hw" -> Route.Hw; "settings" -> Route.Settings; else -> Route.Home })
            },
            onCenter = { toggleStudio() })
    }) { pad ->
        val key = (if (studio) "s:" else "m:") + current
        val order = listOf("m:home", "m:schedule", "m:hw", "m:settings", "s:send", "s:remind", "s:texts", "s:test")
        androidx.compose.animation.AnimatedContent(
            targetState = key,
            modifier = Modifier.padding(pad).consumeWindowInsets(pad),
            transitionSpec = {
                val modeSwitch = initialState.take(2) != targetState.take(2)
                if (modeSwitch) {
                    // вход в Студию / выход: «из чёрной дыры» — масштаб из центра
                    (androidx.compose.animation.scaleIn(initialScale = 0.6f, animationSpec = androidx.compose.animation.core.tween(380)) +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(380))) togetherWith
                        (androidx.compose.animation.scaleOut(targetScale = 0.6f, animationSpec = androidx.compose.animation.core.tween(260)) +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)))
                } else {
                    val dir = if (order.indexOf(targetState) > order.indexOf(initialState)) 1 else -1
                    (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { w -> dir * w / 4 } +
                        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280))) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(240)) { w -> -dir * w / 4 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
                }
            },
            label = "tabs",
        ) { k ->
            when (k) {
                "m:home" -> HomeScreen(push)
                "m:schedule" -> ScheduleScreen(push)
                "m:hw" -> HwScreen()
                "m:settings" -> SettingsScreen(push)
                "s:send" -> StudioScreen(push)
                "s:remind" -> StudioRemindTab()
                "s:texts" -> TemplatesScreen(onBack = null)
                "s:test" -> TestScreen(onBack = null)
                else -> {}
            }
        }
    }
}

@Composable
fun SubScreen(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = actions,
            )
        },
        content = content,
    )
}
