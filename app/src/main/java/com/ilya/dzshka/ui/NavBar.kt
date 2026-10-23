package com.ilya.dzshka.ui

import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
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
        BlackHole(studio, pull, Modifier.align(Alignment.TopCenter).offset(y = (-34).dp), onCenter)
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

private val WARM = listOf(com.ilya.dzshka.R.drawable.bh_warm_0, com.ilya.dzshka.R.drawable.bh_warm_1, com.ilya.dzshka.R.drawable.bh_warm_2,
    com.ilya.dzshka.R.drawable.bh_warm_3, com.ilya.dzshka.R.drawable.bh_warm_4, com.ilya.dzshka.R.drawable.bh_warm_5)
private val COLD = listOf(com.ilya.dzshka.R.drawable.bh_cold_0, com.ilya.dzshka.R.drawable.bh_cold_1, com.ilya.dzshka.R.drawable.bh_cold_2,
    com.ilya.dzshka.R.drawable.bh_cold_3, com.ilya.dzshka.R.drawable.bh_cold_4, com.ilya.dzshka.R.drawable.bh_cold_5)

/**
 * Центральная кнопка «Студия»: круглая кнопка, внутри и вокруг — чёрная дыра с наклонённым диском, как в «Интерстелларе».
 * Картинка не нарисована, а посчитана: трассировка лучей света вокруг чёрной дыры (6 кадров, диск течёт по кругу).
 * В режиме Студии — холодный диск и крестик.
 */
@Composable
private fun BlackHole(studio: Boolean, pull: Float, modifier: Modifier, onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "bh")
    val f by inf.animateFloat(0f, 6f, infiniteRepeatable(tween(if (studio) 1800 else 3000, easing = LinearEasing)), label = "f")
    val glow by inf.animateFloat(0.7f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val frames = if (studio) COLD else WARM
    val i = f.toInt() % 6
    val frac = f - f.toInt()
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.size(width = 180.dp, height = 92.dp)
            .graphicsLayer { val s = press.value * (1f + 0.35f * pull); scaleX = s; scaleY = s; rotationZ = pull * 25f }
            .semantics { contentDescription = if (studio) "Выйти из Студии" else "Студия" }
            .testTag("studio"),
        contentAlignment = Alignment.Center,
    ) {
        // сама кнопка — круг 66 dp (в 1,5 раза больше обычной), с тёмной серединой и светящимся ободком
        val ring = if (studio) Color(0xFF4FC3F7) else Color(0xFFFFA040)
        Box(
            Modifier.size(66.dp).shadow(12.dp, CircleShape, ambientColor = ring, spotColor = ring).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF15102A), Color(0xFF05040C))))
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    scope.launch { press.snapTo(0.86f); press.animateTo(1f, spring(0.35f, 600f)) }
                    onClick()
                },
        )
        // чёрная дыра: два соседних кадра плавно перетекают
        val m = Modifier.size(width = 180.dp, height = 90.dp).graphicsLayer { alpha = glow.coerceAtLeast(0.85f) }
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(frames[i]), null, m.graphicsLayer { alpha = 1f - frac })
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(frames[(i + 1) % 6]), null, m.graphicsLayer { alpha = frac })
        // ободок кнопки поверх диска
        Box(Modifier.size(66.dp).clip(CircleShape).background(Color.Transparent)
            .then(Modifier.drawBehind { drawCircle(ring.copy(alpha = 0.55f * glow), style = Stroke(1.5.dp.toPx())) }))
        if (studio) Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(20.dp).offset(y = (-2).dp))
    }
}
