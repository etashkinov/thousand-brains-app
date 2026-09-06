package com.eta.tbp.app.ui

import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp

private const val TAG = "TouchCapture"
private val STROKE_WIDTH = 20.dp
private val STROKE_FEATHER_RADIUS = 2.dp
private val STROKE_COLOR = Color(0xFF4FC3F7) // light blue

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
 */
@Composable
fun DrawingCanvas(
    strokes: List<List<Offset>>,
    onStrokeCompleted: (List<Offset>) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Canvas(
        modifier =
            modifier
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
