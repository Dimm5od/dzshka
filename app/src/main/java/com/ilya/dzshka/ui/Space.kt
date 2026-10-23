package com.ilya.dzshka.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * Фон ночной темы: отрендеренная картинка космоса (туманности, Млечный путь, галактика, газовый гигант с кольцами, луна)
 * + живой слой поверх: картинка еле заметно «дышит» и плывёт, часть звёзд мерцает, иногда пролетает падающая звезда.
 */
@Composable
fun SpaceBackdrop(modifier: Modifier = Modifier.fillMaxSize()) {
    val stars = remember {
        val rnd = Random(7)
        List(60) { Star(rnd.nextFloat(), rnd.nextFloat(), if (rnd.nextFloat() > 0.85f) 1.6f else 1.0f,
            when (rnd.nextInt(4)) { 0 -> Color(0xFFBBD7FF); 1 -> Color(0xFFFFE2B8); else -> Color.White }, 1f, rnd.nextFloat()) }
    }
    val inf = rememberInfiniteTransition(label = "space")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t")
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.ilya.dzshka.R.drawable.space_bg), null,
            Modifier.fillMaxSize().graphicsLayer {
                val k = 1.06f + 0.015f * sin(t * 2f * Math.PI.toFloat())
                scaleX = k; scaleY = k
                translationX = 10f * density * sin(t * 2f * Math.PI.toFloat())
                translationY = 14f * density * cos(t * 2f * Math.PI.toFloat())
            },
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            stars.forEach { s ->
                val a = 0.5f + 0.5f * sin((t * 30f + s.ph) * 2f * Math.PI.toFloat())
                drawCircle(s.c.copy(alpha = a * 0.9f), s.r * density, Offset(s.x * w, s.y * h))
                drawCircle(s.c.copy(alpha = a * 0.25f), s.r * 3f * density, Offset(s.x * w, s.y * h))
            }
            // падающая звезда раз в ~12 секунд
            val sp = (t * 5f) % 1f
            if (sp < 0.10f) {
                val k = sp / 0.10f
                val sx = w * (0.10f + 0.65f * k); val sy = h * (0.06f + 0.22f * k)
                drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White), Offset(sx - 80 * density, sy - 30 * density), Offset(sx, sy)),
                    Offset(sx - 80 * density, sy - 30 * density), Offset(sx, sy), 1.8f * density, StrokeCap.Round, alpha = 1f - k)
                drawCircle(Color.White.copy(alpha = 1f - k), 2f * density, Offset(sx, sy))
            }
        }
    }
}
