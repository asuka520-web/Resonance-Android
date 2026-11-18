package com.resonance.app

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun Modifier.pressMotion(interaction: InteractionSource, pressedScale: Float = .96f): Modifier {
    val pressed = interaction.collectIsPressedAsState().value
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) tween(95) else spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
        label = "press"
    )
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

@Composable
internal fun GlassSurface(
    modifier: Modifier,
    radius: Dp,
    tint: Color,
    elevation: Dp = 0.dp,
    artwork: ImageBitmap? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.shadow(elevation, shape, clip = false).clip(shape).background(tint)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(Color.White.copy(alpha = .30f), Color.White.copy(alpha = .04f), Color.White.copy(alpha = .15f))
                ),
                shape = shape
            )
    ) {
        // Blur only the artwork layer. Foreground text and artwork controls stay crisp.
        if (artwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Image(
                artwork, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = .30f }.blur(24.dp)
            )
        }
        Box(Modifier.matchParentSize().drawWithCache {
            val reflection = Brush.radialGradient(
                listOf(Color.White.copy(alpha = .16f), Color.White.copy(alpha = .025f), Color.Transparent),
                center = Offset(size.width * .16f, -size.height * .25f),
                radius = size.width.coerceAtLeast(size.height) * .86f
            )
            val shade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .20f)))
            onDrawBehind {
                drawRect(shade)
                drawRect(reflection)
            }
        })
        content()
    }
}
