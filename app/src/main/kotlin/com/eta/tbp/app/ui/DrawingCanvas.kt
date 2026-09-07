package com.eta.tbp.app.ui

import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eta.tbp.lib.orchestrator.PrimitiveOverlay

private const val TAG = "TouchCapture"
private val STROKE_WIDTH = 20.dp
private val STROKE_FEATHER_RADIUS = 2.dp
private val STROKE_COLOR = Color(0xFF4FC3F7) // light blue
private val PRIMITIVE_OVERLAY_COLOR = Color(0xFFFF5722) // orange, distinct from the stroke color
private val PRIMITIVE_OVERLAY_STROKE_WIDTH = 2.dp
private val PRIMITIVE_OVERLAY_PADDING = 10.dp // so a near-zero-width line/arc box still reads as a rectangle
private val PRIMITIVE_OVERLAY_MIN_SIDE = 32.dp // keeps very short primitives' boxes visible
private val PRIMITIVE_OVERLAY_LABEL_GAP = 6.dp
private val PRIMITIVE_OVERLAY_LABEL_SIZE = 12.sp

/**
 * Captures raw [MotionEvent]s and renders strokes live. Contains no
 * recognition logic itself — it's the `app`-side adapter that hands off a
 * completed stroke's raw points to the caller, kept separate from the
 * brain per the lib/app split.
 *
 * @param strokes completed strokes, hoisted so a toolbar (undo/clear) can
 *   act on the same list this canvas renders.
 * @param onStrokeCompleted invoked with the newly-completed stroke's points
 *   whenever one finishes.
 * @param enabled when false, touches are ignored — used while a
 *   recognition result is showing and no further strokes should be fed in.
 * @param primitiveOverlays debug boxes drawn around each detected primitive
 *   with its measurement — the on-canvas counterpart of [LmStateOverlay]'s
 *   text-only primitive list, sourced from the same
 *   [com.eta.tbp.lib.orchestrator.MontyOrchestrator] state. Empty (the
 *   default) draws nothing extra.
 */
@Composable
fun DrawingCanvas(
    strokes: List<List<Offset>>,
    onStrokeCompleted: (List<Offset>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primitiveOverlays: List<PrimitiveOverlay> = emptyList(),
) {
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val overlayLabelPaint =
        remember {
            Paint().apply {
                color = PRIMITIVE_OVERLAY_COLOR.toArgb()
                isAntiAlias = true
            }
        }

    // Two stacked canvases, not one: .blur() below is a graphics-layer effect
    // that blurs everything drawn inside that DrawScope, not just the stroke
    // lines it's meant to feather. The primitive overlay needs to stay sharp,
    // so it's drawn in its own unblurred Canvas on top rather than inside the
    // blurred one — both fillMaxSize() the same Box, so their coordinate
    // spaces line up exactly.
    Box(modifier = modifier) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .pointerInteropFilter { event ->
                        if (!enabled) return@pointerInteropFilter false
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                activeStroke = pointsFrom(event)
                            }

                            MotionEvent.ACTION_MOVE -> {
                                activeStroke = activeStroke + pointsFrom(event)
                            }

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                activeStroke = activeStroke + pointsFrom(event)
                                onStrokeCompleted(activeStroke)
                                activeStroke = emptyList()
                            }

                            else -> {
                                return@pointerInteropFilter false
                            }
                        }
                        true
                    }.blur(STROKE_FEATHER_RADIUS),
        ) {
            for (stroke in strokes) {
                drawStroke(stroke)
            }
            drawStroke(activeStroke)
        }
        if (primitiveOverlays.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawPrimitiveOverlays(primitiveOverlays, overlayLabelPaint)
            }
        }
    }
}

/** Includes MotionEvent's batched historical points, not just the latest sample. */
private fun pointsFrom(event: MotionEvent): List<Offset> {
    val points = ArrayList<Offset>(event.historySize + 1)
    for (h in 0 until event.historySize) {
        points += Offset(event.getHistoricalX(h), event.getHistoricalY(h))
    }
    points += Offset(event.x, event.y)
    return points
}

private fun DrawScope.drawStroke(points: List<Offset>) {
    val strokeWidthPx = STROKE_WIDTH.toPx()
    for (i in 0 until points.size - 1) {
        drawLine(
            color = STROKE_COLOR,
            start = points[i],
            end = points[i + 1],
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * One rectangle per detected primitive, in the same raw touch-pixel
 * coordinate space [PrimitiveOverlay] already reports its bounds in (see
 * that class's doc) — no coordinate mapping needed here beyond padding a
 * degenerate (near-zero-width) box out to something visible. The label
 * uses [describe] from [LmStateOverlay.kt], the same formatting the
 * text-only debug panel uses, so both views of the same data read
 * identically.
 */
private fun DrawScope.drawPrimitiveOverlays(
    overlays: List<PrimitiveOverlay>,
    labelPaint: Paint,
) {
    if (overlays.isEmpty()) return
    val padding = PRIMITIVE_OVERLAY_PADDING.toPx()
    val minSide = PRIMITIVE_OVERLAY_MIN_SIDE.toPx()
    val strokeWidthPx = PRIMITIVE_OVERLAY_STROKE_WIDTH.toPx()
    labelPaint.textSize = PRIMITIVE_OVERLAY_LABEL_SIZE.toPx()

    for (overlay in overlays) {
        val centerX = (overlay.topLeft.x + overlay.bottomRight.x) / 2f
        val centerY = (overlay.topLeft.y + overlay.bottomRight.y) / 2f
        val width = maxOf(overlay.bottomRight.x - overlay.topLeft.x + 2 * padding, minSide)
        val height = maxOf(overlay.bottomRight.y - overlay.topLeft.y + 2 * padding, minSide)
        val rectLeft = centerX - width / 2f
        val rectTop = centerY - height / 2f

        drawRect(
            color = PRIMITIVE_OVERLAY_COLOR,
            topLeft = Offset(rectLeft, rectTop),
            size = Size(width, height),
            style = Stroke(width = strokeWidthPx),
        )
        drawContext.canvas.nativeCanvas.drawText(
            describe(overlay.measurement),
            rectLeft,
            rectTop - PRIMITIVE_OVERLAY_LABEL_GAP.toPx(),
            labelPaint,
        )
    }
}
