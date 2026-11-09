package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.exp
import kotlin.math.max

// спектр частот
@Composable
fun M3StudioSegmentedVisualizer(
    bandsFlow: StateFlow<FloatArray>?,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = accentColor,
    segmentCount: Int = 14
) {
    if (!isActive) return

    val numBars = 7
    val currentLevels = remember { FloatArray(numBars) { 0.04f } }
    val currentPeaks = remember { FloatArray(numBars) { 0.04f } }

    var frameNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isActive, isPlaying) {
        if (!isActive || !isPlaying) {
            for (i in 0 until numBars) {
                currentLevels[i] = 0.04f
                currentPeaks[i] = 0.04f
            }
            return@LaunchedEffect
        }
        while (isActive && isPlaying) {
            withFrameNanos { now ->
                frameNanos = now
            }
        }
    }

    val unlitColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
    val unlitBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
    val capColor = Color.White.copy(alpha = 0.96f)

    var lastNanos by remember { mutableLongStateOf(0L) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val now = frameNanos
        val dt = if (lastNanos == 0L || now == 0L) 0.016f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastNanos = now

        val rawBands = bandsFlow?.value ?: FloatArray(5) { 0.04f }
        val b0 = rawBands.getOrElse(0) { 0.04f }.coerceIn(0.04f, 1f)
        val b1 = rawBands.getOrElse(1) { 0.04f }.coerceIn(0.04f, 1f)
        val b2 = rawBands.getOrElse(2) { 0.04f }.coerceIn(0.04f, 1f)
        val b3 = rawBands.getOrElse(3) { 0.04f }.coerceIn(0.04f, 1f)
        val b4 = rawBands.getOrElse(4) { 0.04f }.coerceIn(0.04f, 1f)

        val target0 = b0
        val target1 = (b0 * 0.65f + b1 * 0.35f).coerceIn(0.04f, 1f)
        val target2 = (b1 * 0.85f + b2 * 0.15f).coerceIn(0.04f, 1f)
        val target3 = b2
        val target4 = (b2 * 0.35f + b3 * 0.65f).coerceIn(0.04f, 1f)
        val target5 = b3
        val target6 = (b3 * 0.25f + b4 * 0.75f).coerceIn(0.04f, 1f)

        val targets = floatArrayOf(target0, target1, target2, target3, target4, target5, target6)

        for (i in 0 until numBars) {
            val target = targets[i]
            val cur = currentLevels[i]
            val speed = if (target > cur) 24f else 9f
            val factor = (1f - exp(-speed * dt)).coerceIn(0f, 1f)
            val next = cur + (target - cur) * factor
            currentLevels[i] = next

            val peak = currentPeaks[i]
            val peakFall = 0.42f * dt
            currentPeaks[i] = if (next > peak) next else max(next, peak - peakFall)
        }

        val totalWidth = size.width
        val totalHeight = size.height

        val colGap = 10.dp.toPx()
        val availableWidth = (totalWidth - (numBars - 1) * colGap).coerceAtLeast(1f)
        val colWidth = availableWidth / numBars
        val barCorner = CornerRadius(colWidth / 2f, colWidth / 2f)

        val minBarHeight = 12.dp.toPx()
        val maxBarHeight = totalHeight - 20.dp.toPx()
        val peakCapHeight = 4.dp.toPx()
        val peakCapCorner = CornerRadius(peakCapHeight / 2f, peakCapHeight / 2f)

        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.28f),
                    secondaryColor.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                center = Offset(totalWidth / 2f, totalHeight * 0.65f),
                radius = totalWidth * 0.75f
            )
        )

        for (i in 0 until numBars) {
            val left = i * (colWidth + colGap)
            val level = currentLevels[i]
            val peak = currentPeaks[i]

            drawRoundRect(
                color = unlitColor,
                topLeft = Offset(left, 0f),
                size = Size(colWidth, totalHeight),
                cornerRadius = barCorner
            )

            val activeHeight = (minBarHeight + (maxBarHeight - minBarHeight) * level).coerceIn(minBarHeight, totalHeight)
            val barTop = totalHeight - activeHeight

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        capColor,
                        accentColor,
                        secondaryColor.copy(alpha = 0.85f)
                    ),
                    startY = barTop,
                    endY = totalHeight
                ),
                topLeft = Offset(left, barTop),
                size = Size(colWidth, activeHeight),
                cornerRadius = barCorner
            )

            if (isPlaying && peak > level && peak > 0.12f) {
                val peakTop = (totalHeight - (minBarHeight + (maxBarHeight - minBarHeight) * peak) - peakCapHeight).coerceIn(2.dp.toPx(), totalHeight - peakCapHeight)
                drawRoundRect(
                    color = capColor,
                    topLeft = Offset(left, peakTop),
                    size = Size(colWidth, peakCapHeight),
                    cornerRadius = peakCapCorner
                )
            }
        }
    }
}

// статический визуализатор
@Composable
fun M3StudioSegmentedVisualizer(
    bands: FloatArray,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = accentColor,
    segmentCount: Int = 14
) {
    val dummyFlow = remember(bands) { kotlinx.coroutines.flow.MutableStateFlow(bands) }
    M3StudioSegmentedVisualizer(
        bandsFlow = dummyFlow,
        isActive = true,
        isPlaying = isPlaying,
        modifier = modifier,
        accentColor = accentColor,
        secondaryColor = secondaryColor,
        segmentCount = segmentCount
    )
}
