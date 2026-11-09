package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// пружинный клик
@Composable
fun Modifier.bouncyPress(
    interactionSource: MutableInteractionSource,
    scaleDown: Float = 0.95f
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = 450f
        ),
        label = "bouncyScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    scaleDown: Float = 0.95f,
    showRipple: Boolean = false,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .bouncyPress(interactionSource = interactionSource, scaleDown = scaleDown)
        .clickable(
            interactionSource = interactionSource,
            indication = if (showRipple) ripple() else null,
            enabled = enabled,
            onClick = onClick
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.bouncyCombinedClickable(
    enabled: Boolean = true,
    scaleDown: Float = 0.96f,
    showRipple: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this
        .bouncyPress(interactionSource = interactionSource, scaleDown = scaleDown)
        .combinedClickable(
            interactionSource = interactionSource,
            indication = if (showRipple) ripple() else null,
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick
        )
}
