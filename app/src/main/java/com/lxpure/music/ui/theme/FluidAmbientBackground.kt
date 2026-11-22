package com.lxpure.music.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.lxpure.music.core.player.AmbientPalette
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Fluid ambient mesh gradient background inspired by Apple Music / Salt Player.
 * Smoothly crossfades album cover colors with subtle organic breathing drift.
 */
@Composable
fun FluidAmbientBackground(
    palette: AmbientPalette,
    enabled: Boolean = true,
    immersive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val lxColors = LocalLxColors.current
    val baseColor = if (immersive) Color(0xFF090B10) else lxColors.background

    val primary by animateColorAsState(
        targetValue = palette.primary,
        animationSpec = tween(durationMillis = 900),
        label = "ambientPrimary"
    )
    val secondary by animateColorAsState(
        targetValue = palette.secondary,
        animationSpec = tween(durationMillis = 900),
        label = "ambientSecondary"
    )
    val accent by animateColorAsState(
        targetValue = palette.accent,
        animationSpec = tween(durationMillis = 900),
        label = "ambientAccent"
    )

    // Only run continuous drift animation inside the active full-screen player;
    // keeps Home/Discover/Search static at 0 extra GPU redraws per second.
    val phase = if (immersive && enabled) {
        val transition = rememberInfiniteTransition(label = "fluidDrift")
        val animatedPhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 22_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "fluidPhase"
        )
        animatedPhase
    } else {
        0f
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = baseColor)
        if (!enabled) return@Canvas

        val w = size.width
        val h = size.height

        if (immersive) {
            val orb1 = Offset(
                x = w * (0.24f + 0.14f * cos(phase)),
                y = h * (0.20f + 0.08f * sin(phase))
            )
            val orb2 = Offset(
                x = w * (0.80f + 0.12f * sin(phase * 0.8f)),
                y = h * (0.58f + 0.10f * cos(phase * 0.9f))
            )
            val orb3 = Offset(
                x = w * (0.32f + 0.16f * sin(phase + 1.4f)),
                y = h * (0.86f + 0.07f * cos(phase + 1.4f))
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = 0.78f), Color.Transparent),
                    center = orb1,
                    radius = w * 1.15f
                ),
                center = orb1,
                radius = w * 1.15f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(secondary.copy(alpha = 0.62f), Color.Transparent),
                    center = orb2,
                    radius = w * 1.05f
                ),
                center = orb2,
                radius = w * 1.05f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.34f), Color.Transparent),
                    center = orb3,
                    radius = w * 0.95f
                ),
                center = orb3,
                radius = w * 0.95f
            )
            // Soft dark scrim to keep white text and lyrics effortlessly legible
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.22f),
                        Color.Black.copy(alpha = 0.32f),
                        Color.Black.copy(alpha = 0.56f)
                    )
                )
            )
        } else {
            // Subtle top ambient wash for main screens
            val alphaScale = if (lxColors.isDark) 0.24f else 0.12f
            val topOrb = Offset(w * 0.22f, h * 0.04f)
            val sideOrb = Offset(w * 0.88f, h * 0.12f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primary.copy(alpha = alphaScale), Color.Transparent),
                    center = topOrb,
                    radius = w * 0.9f
                ),
                center = topOrb,
                radius = w * 0.9f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = alphaScale * 0.7f), Color.Transparent),
                    center = sideOrb,
                    radius = w * 0.75f
                ),
                center = sideOrb,
                radius = w * 0.75f
            )
        }
    }
}
