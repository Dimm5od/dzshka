package com.ilya.dzshka.ui

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

data class NavItem(val key: String, val icon: ImageVector, val label: String)

/**
 * Нижняя панель: 4 вкладки по бокам и большая круглая «Студия» по центру — как чёрная дыра, вид сверху.
 * При переключении режима вкладки «засасываются» в центр и появляются новые.
 * @param pull 0 — вкладки на месте, 1 — втянуты в центр (анимация смены режима)
 */
@Composable
fun DzBottomBar(
    items: List<NavItem>,          // ровно 4: две слева, две справа
    selected: String?,
    studio: Boolean,
    pull: Float,
    onSelect: (String) -> Unit,
    onCenter: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // чёрная дыра — поверх панели и выше её края, поэтому вне Surface (Surface обрезает содержимое)
    Box(Modifier.fillMaxWidth()) {
        Surface(color = cs.surfaceContainer, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                items.forEachIndexed { i, it ->
                    if (i == 2) Spacer(Modifier.width(84.dp))
                    // куда «втягивается» вкладка: к центру панели
                    val side = if (i < 2) 1f else -1f
                    val dist = if (i == 0 || i == 3) 2.2f else 1f
                    NavCell(it, selected == it.key, Modifier.weight(1f).graphicsLayer {
                        translationX = side * dist * 70f * pull * density
                        scaleX = 1f - pull; scaleY = 1f - pull
                        alpha = 1f - pull
                        rotationZ = side * 180f * pull
                    }) { onSelect(it.key) }
                }
            }
        }
        BlackHole(studio, pull, Modifier.align(Alignment.TopCenter).offset(y = (-30).dp), onCenter)
    }
}

@Composable
private fun NavCell(it: NavItem, sel: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scale by animateFloatAsState(if (sel) 1.2f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "s")
    val tint by animateColorAsState(if (sel) cs.primary else cs.onSurfaceVariant, label = "c")
    Column(
        modifier.fillMaxHeight()
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .semantics { selected = sel; role = Role.Tab },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, contentAlignment = Alignment.Center) {
            val bg by animateFloatAsState(if (sel) 1f else 0f, label = "bg")
            Box(Modifier.size(width = 54.dp, height = 30.dp).graphicsLayer { scaleX = bg; alpha = bg }.clip(CircleShape).background(cs.primary.copy(alpha = 0.16f)))
            Icon(it.icon, null, tint = tint)
        }
        Spacer(Modifier.height(3.dp))
        Text(it.label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/**
 * Центральная кнопка «Студия» — чёрная дыра как в «Интерстелларе»: тёмный шар, яркий диск поперёк,
 * над шаром — изогнутое изображение дальней части диска, снизу — тонкое кольцо. Диск переливается,
 * по нему бегут искры. В режиме Студии — холодные цвета и крестик.
 */
@Composable
private fun BlackHole(studio: Boolean, pull: Float, modifier: Modifier, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "bh")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(if (studio) 2200 else 4200, easing = LinearEasing)), label = "t")
    val glow by inf.animateFloat(0.75f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    // тёплые цвета диска / холодные в Студии
    val hot = if (studio) Color(0xFFE0F7FF) else Color(0xFFFFF4D6)
    val mid = if (studio) Color(0xFF4FC3F7) else Color(0xFFFFA040)
    val deep = if (studio) Color(0xFF7C4DFF) else Color(0xFFD84315)
    Box(
        modifier.size(width = 124.dp, height = 78.dp)
            .graphicsLayer { val s = press.value * (1f + 0.3f * pull); scaleX = s; scaleY = s }
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                scope.launch { press.snapTo(0.86f); press.animateTo(1f, spring(0.35f, 600f)) }
                onClick()
            }
            .semantics { contentDescription = if (studio) "Выйти из Студии" else "Студия" }
            .testTag("studio"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f + 4.dp.toPx())
            val R = 15.dp.toPx()                 // горизонт событий
            val a = R * 3.9f; val b = R * 0.22f  // полуоси диска (тонкий, длинный)
            val flash = (1f + pull * 1.5f).coerceAtMost(2f)
            fun arcRing(r: Float, w: Float, start: Float, sweep: Float, brush: Brush, alpha: Float) = drawArc(brush, start, sweep, false,
                topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round), alpha = alpha)
            val warm = Brush.horizontalGradient(listOf(deep, mid, hot, Color.White, hot, mid, deep), c.x - R * 1.7f, c.x + R * 1.7f)
            // общее свечение
            drawCircle(Brush.radialGradient(listOf(mid.copy(alpha = 0.45f * glow * flash), deep.copy(alpha = 0.15f), Color.Transparent), c, R * 2.8f), R * 2.8f, c)
            // свечение вдоль диска
            drawOval(Brush.horizontalGradient(listOf(Color.Transparent, mid.copy(alpha = 0.45f * glow), Color.Transparent), c.x - a, c.x + a),
                topLeft = Offset(c.x - a, c.y - b * 3.2f), size = androidx.compose.ui.geometry.Size(a * 2, b * 6.4f))
            // верхний ореол — дальняя часть диска, загнутая гравитацией над шаром (как в «Интерстелларе»)
            arcRing(R * 1.62f, R * 0.30f, 172f, 196f, warm, 0.30f * glow)
            arcRing(R * 1.40f, R * 0.30f, 172f, 196f, warm, 0.65f * glow)
            arcRing(R * 1.18f, R * 0.26f, 175f, 190f, warm, 0.95f)
            // нижнее тонкое кольцо
            arcRing(R * 1.12f, R * 0.09f, 18f, 144f, warm, 0.85f)
            // сам шар
            drawCircle(Color.Black, R, c)
            drawCircle(hot.copy(alpha = 0.6f), R, c, style = Stroke(width = 1.dp.toPx()))
            // передний диск поперёк: слева ярче (летит к нам)
            drawOval(Brush.horizontalGradient(listOf(Color.Transparent, deep, mid, hot, Color.White, Color.White, hot, mid, deep.copy(alpha = 0.6f), Color.Transparent),
                c.x - a, c.x + a), topLeft = Offset(c.x - a, c.y - b), size = androidx.compose.ui.geometry.Size(a * 2, b * 2), alpha = glow)
            drawOval(Brush.horizontalGradient(listOf(Color.Transparent, Color.White, Color.Transparent), c.x - a * 0.55f, c.x + a * 0.55f),
                topLeft = Offset(c.x - a * 0.55f, c.y - b * 0.3f), size = androidx.compose.ui.geometry.Size(a * 1.1f, b * 0.6f))
            // искры, бегущие по диску
            for (k in 0 until 4) {
                val ph = (t + k / 4f) % 1f
                val x = c.x + a * 0.9f * (1f - 2f * ph)
                val fade = kotlin.math.sin(Math.PI * ph).toFloat()
                drawCircle(Color.White.copy(alpha = 0.9f * fade), 1.3.dp.toPx(), Offset(x, c.y))
            }
            // крестик выхода в режиме Студии
            if (studio) {
                val q = R * 0.38f
                drawLine(Color.White, Offset(c.x - q, c.y - q - R * 0.35f), Offset(c.x + q, c.y + q - R * 0.35f), 2.2.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(Color.White, Offset(c.x + q, c.y - q - R * 0.35f), Offset(c.x - q, c.y + q - R * 0.35f), 2.2.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}
