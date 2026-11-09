package com.example.ui.components

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// кнопка назад
typealias ScallopedPrevButtonShape = ScallopedPreviousButtonShape

class ScallopedPreviousButtonShape(
    private val cutoutDepthDp: Dp = 10.dp,
    private val cornerRadiusDp: Dp = 26.dp
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply {
            with(density) {
                val w = size.width
                val h = size.height
                val rOuter = cornerRadiusDp.toPx().coerceAtMost(h / 2f)
                val depth = cutoutDepthDp.toPx().coerceAtMost(w * 0.45f)
                val cy = h / 2f

                moveTo(rOuter, 0f)
                lineTo(w, 0f)
                cubicTo(
                    x1 = w - depth * 0.35f, y1 = cy * 0.4f,
                    x2 = w - depth, y2 = cy * 0.8f,
                    x3 = w - depth, y3 = cy
                )
                cubicTo(
                    x1 = w - depth, y1 = cy * 1.2f,
                    x2 = w - depth * 0.35f, y2 = cy * 1.6f,
                    x3 = w, y3 = h
                )
                lineTo(rOuter, h)
                arcTo(
                    rect = Rect(0f, h - 2 * rOuter, 2 * rOuter, h),
                    startAngleDegrees = 90f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                lineTo(0f, rOuter)
                arcTo(
                    rect = Rect(0f, 0f, 2 * rOuter, 2 * rOuter),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                close()
            }
        }
        return Outline.Generic(path)
    }
}

// кнопка вперед
class ScallopedNextButtonShape(
    private val cutoutDepthDp: Dp = 10.dp,
    private val cornerRadiusDp: Dp = 26.dp
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path().apply {
            with(density) {
                val w = size.width
                val h = size.height
                val rOuter = cornerRadiusDp.toPx().coerceAtMost(h / 2f)
                val depth = cutoutDepthDp.toPx().coerceAtMost(w * 0.45f)
                val cy = h / 2f

                moveTo(0f, 0f)
                lineTo(w - rOuter, 0f)
                arcTo(
                    rect = Rect(w - 2 * rOuter, 0f, w, 2 * rOuter),
                    startAngleDegrees = 270f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                lineTo(w, h - rOuter)
                arcTo(
                    rect = Rect(w - 2 * rOuter, h - 2 * rOuter, w, h),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 90f,
                    forceMoveTo = false
                )
                lineTo(0f, h)
                cubicTo(
                    x1 = depth * 0.35f, y1 = cy * 1.6f,
                    x2 = depth, y2 = cy * 1.2f,
                    x3 = depth, y3 = cy
                )
                cubicTo(
                    x1 = depth, y1 = cy * 0.8f,
                    x2 = depth * 0.35f, y2 = cy * 0.4f,
                    x3 = 0f, y3 = 0f
                )
                close()
            }
        }
        return Outline.Generic(path)
    }
}
