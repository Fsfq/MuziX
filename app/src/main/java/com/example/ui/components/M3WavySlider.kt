package com.example.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

@Composable
fun M3WavySlider(
    positionFlow: StateFlow<Long>? = null,
    positionProvider: () -> Long = { 0L },
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primaryContainer,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    isWavy: Boolean = false
) {
    val livePosition = if (positionFlow != null) {
        val flowPos by positionFlow.collectAsStateWithLifecycle()
        flowPos
    } else {
        positionProvider()
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    val thumbHeight by animateFloatAsState(
        targetValue = if (isDragging) 28f else 22f,
        animationSpec = tween(150),
        label = "thumbHeight"
    )
    val thumbWidth by animateFloatAsState(
        targetValue = if (isDragging) 8f else 6f,
        animationSpec = tween(150),
        label = "thumbWidth"
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { offset ->
                        val newProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((newProgress * durationMs).toLong())
                    }
                }
                .pointerInput(durationMs) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            isDragging = false
                            onSeek((dragProgress * durationMs).toLong())
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragProgress = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
            ) {
                val pos = livePosition
                val actualProgress = if (durationMs > 0) (pos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
                val currentProgress = if (isDragging) dragProgress else actualProgress

                val width = size.width
                val height = size.height
                val midY = height / 2f
                val activeWidth = (width * currentProgress).coerceIn(0f, width)
                val trackThickness = 8.dp.toPx()
                val cornerRadius = CornerRadius(trackThickness / 2f, trackThickness / 2f)

                drawRoundRect(
                    color = inactiveColor,
                    topLeft = Offset(0f, midY - trackThickness / 2f),
                    size = Size(width, trackThickness),
                    cornerRadius = cornerRadius
                )

                if (activeWidth > 0f) {
                    drawRoundRect(
                        color = activeColor,
                        topLeft = Offset(0f, midY - trackThickness / 2f),
                        size = Size(activeWidth, trackThickness),
                        cornerRadius = cornerRadius
                    )
                }

                val tWidthPx = thumbWidth.dp.toPx()
                val tHeightPx = thumbHeight.dp.toPx()
                val thumbX = (activeWidth - tWidthPx / 2f).coerceIn(0f, width - tWidthPx)
                val thumbY = midY - tHeightPx / 2f

                drawRoundRect(
                    color = activeColor,
                    topLeft = Offset(thumbX, thumbY),
                    size = Size(tWidthPx, tHeightPx),
                    cornerRadius = CornerRadius(tWidthPx / 2f, tWidthPx / 2f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val displayPos = if (isDragging) (dragProgress * durationMs).toLong() else livePosition
            Text(
                text = formatDuration(displayPos),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun M3WavySlider(
    positionProvider: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primaryContainer,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    isWavy: Boolean = false
) {
    M3WavySlider(
        positionFlow = null,
        positionProvider = positionProvider,
        durationMs = durationMs,
        onSeek = onSeek,
        modifier = modifier,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        isWavy = isWavy
    )
}

@Composable
fun M3WavySlider(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primaryContainer,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    isWavy: Boolean = false
) {
    M3WavySlider(
        positionFlow = null,
        positionProvider = { positionMs },
        durationMs = durationMs,
        onSeek = onSeek,
        modifier = modifier,
        activeColor = activeColor,
        inactiveColor = inactiveColor,
        isWavy = isWavy
    )
}

fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
