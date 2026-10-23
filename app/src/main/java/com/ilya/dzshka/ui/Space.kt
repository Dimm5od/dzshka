package com.ilya.dzshka.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private class Star(val x: Float, val y: Float, val r: Float, val c: Color, val tw: Float, val ph: Float)

/** Фон ночной темы: космос — звёзды мерцают, туманности, спиральная галактика, планета с кольцом, падающие звёзды. */
@Composable
fun SpaceBackdrop(modifier: Modifier = Modifier.fillMaxSize()) {
    val stars = remember {
        val rnd = Random(7)
        List(230) {
            val t = rnd.nextFloat()
            Star(
                x = rnd.nextFloat(), y = rnd.nextFloat(),
                r = if (t > 0.96f) 1.9f else if (t > 0.8f) 1.3f else 0.8f,
                c = when (rnd.nextInt(6)) { 0 -> Color(0xFFBBD7FF); 1 -> Color(0xFFFFE2B8); 2 -> Color(0xFFE6D6FF); else -> Color.White },
                tw = if (rnd.nextFloat() < 0.3f) rnd.nextFloat() * 0.7f + 0.3f else 0f,
                ph = rnd.nextFloat(),
            )
        }
    }
    val galaxy = remember {
        val rnd = Random(11)
        List(260) {
            val arm = it % 2
            val k = rnd.nextFloat()
            val ang = k * 4.2f + arm * Math.PI.toFloat() + (rnd.nextFloat() - 0.5f) * 0.5f
            val rad = 0.08f + k * 0.92f
            Triple(ang, rad + (rnd.nextFloat() - 0.5f) * 0.08f, rnd.nextFloat())
        }
    }
    val inf = rememberInfiniteTransition(label = "space")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t")

    Canvas(modifier) {
        val w = size.width; val h = size.height
        // глубина космоса
        drawRect(Brush.verticalGradient(listOf(Color(0xFF04040C), Color(0xFF0A0920), Color(0xFF130A2C), Color(0xFF06060F))))
        // туманности
        nebula(Offset(w * 0.15f, h * 0.22f), w * 0.75f, Color(0xFF6A1B9A), 0.28f)
        nebula(Offset(w * 0.95f, h * 0.48f), w * 0.70f, Color(0xFF1565C0), 0.24f)
        nebula(Offset(w * 0.35f, h * 0.88f), w * 0.65f, Color(0xFFAD1457), 0.16f)
        // звёзды (часть мерцает)
        stars.forEach { s ->
            val a = if (s.tw > 0f) 0.35f + 0.65f * (0.5f + 0.5f * sin((t * 40f + s.ph) * 2f * Math.PI.toFloat())) * s.tw + (1 - s.tw) * 0.5f else 0.85f
            drawCircle(s.c.copy(alpha = a.coerceIn(0f, 1f)), s.r * density, Offset(s.x * w, s.y * h))
            if (s.r > 1.5f) {
                drawLine(s.c.copy(alpha = 0.35f * a), Offset(s.x * w - 6 * density, s.y * h), Offset(s.x * w + 6 * density, s.y * h), 0.6f * density)
                drawLine(s.c.copy(alpha = 0.35f * a), Offset(s.x * w, s.y * h - 6 * density), Offset(s.x * w, s.y * h + 6 * density), 0.6f * density)
            }
        }
        // спиральная галактика вдалеке, медленно вращается
        val gc = Offset(w * 0.78f, h * 0.17f); val gr = w * 0.20f
        drawCircle(Brush.radialGradient(listOf(Color(0x88FFE9C4), Color(0x22B388FF), Color.Transparent), gc, gr), gr, gc)
        rotate(t * 360f, gc) {
            galaxy.forEach { (ang, rad, br) ->
                val x = gc.x + cos(ang) * rad * gr
                val y = gc.y + sin(ang) * rad * gr * 0.45f
                drawCircle(Color(0xFFE1D5FF).copy(alpha = 0.25f + 0.5f * br * (1 - rad).coerceAtLeast(0.15f)), (0.5f + br) * density, Offset(x, y))
            }
        }
        drawCircle(Color(0xFFFFF3D9), 2.5f * density, gc)
        // планета с кольцом, внизу справа
        planet(Offset(w * 0.88f, h * 0.80f), w * 0.22f)
        // маленькая луна
        val mc = Offset(w * 0.12f, h * 0.60f); val mr = w * 0.035f
        drawCircle(Brush.radialGradient(listOf(Color(0xFFD7CCC8), Color(0xFF6D4C41), Color(0xFF1B0F0A)), Offset(mc.x - mr * 0.4f, mc.y - mr * 0.4f), mr * 1.6f), mr, mc)
        // падающая звезда раз в ~12 секунд
        val sp = (t * 5f) % 1f
        if (sp < 0.12f) {
            val k = sp / 0.12f
            val sx = w * (0.15f + 0.6f * k); val sy = h * (0.08f + 0.25f * k)
            drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White), Offset(sx - 60 * density, sy - 25 * density), Offset(sx, sy)),
                Offset(sx - 60 * density, sy - 25 * density), Offset(sx, sy), 1.6f * density, StrokeCap.Round, alpha = 1f - k)
        }
    }
}

private fun DrawScope.nebula(c: Offset, r: Float, col: Color, a: Float) {
    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), col.copy(alpha = a * 0.4f), Color.Transparent), c, r), r, c)
}

private fun DrawScope.planet(c: Offset, r: Float) {
    val ringA = r * 1.9f; val ringB = r * 0.42f
    // дальняя половина кольца — за планетой
    clipRect(top = 0f, bottom = c.y) {
        drawOval(Color(0x99D7B98A), Offset(c.x - ringA, c.y - ringB), Size(ringA * 2, ringB * 2), style = Stroke(r * 0.12f))
    }
    // шар: освещён слева сверху
    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFCC80), Color(0xFFE65100), Color(0xFF4E1A04), Color(0xFF120502)),
        Offset(c.x - r * 0.45f, c.y - r * 0.45f), r * 1.7f), r, c)
    // полосы атмосферы
    for (i in -2..2) drawArc(Color(0x22FFFFFF), 200f, 140f, false, Offset(c.x - r, c.y - r * 0.2f + i * r * 0.28f), Size(r * 2, r * 0.5f), style = Stroke(r * 0.05f))
    // ближняя половина кольца — перед планетой
    clipRect(top = c.y, bottom = c.y + ringB * 2) {
        drawOval(Color(0xCCE8CFA0), Offset(c.x - ringA, c.y - ringB), Size(ringA * 2, ringB * 2), style = Stroke(r * 0.12f))
    }
    drawCircle(Brush.radialGradient(listOf(Color(0x33FF9100), Color.Transparent), c, r * 1.6f), r * 1.6f, c)
}
