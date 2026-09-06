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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInteropFilter

private const val TAG = "TouchCapture"
private const val STROKE_WIDTH_PX = 6f

/**
 * Phase-0 scaffold: captures raw [MotionEvent]s and renders strokes live.
 * Deliberately contains no recognition logic — this is the `app`-side
 * adapter that will eventually convert points into `lib`'s
 * `RawTouchObservation`s, kept separate from the brain per the lib/app split.
 */
@Composable
fun DrawingCanvas(modifier: Modifier = Modifier) {
    var completedStrokes by remember { mutableStateOf<List<List<Offset>>>(emptyList()) }
    var activeStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Canvas(
        modifier =
            modifier
                .background(Color.White)
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            activeStroke = pointsFrom(event)
                        }

                        MotionEvent.ACTION_MOVE -> {
                            activeStroke = activeStroke + pointsFrom(event)
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            activeStroke = activeStroke + pointsFrom(event)
                            logStroke(completedStrokes.size, activeStroke)
                            completedStrokes = completedStrokes + listOf(activeStroke)
                            activeStroke = emptyList()
                        }

                        else -> {
                            return@pointerInteropFilter false
                        }
                    }
                    true
                },
    ) {
        for (stroke in completedStrokes) {
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
    for (i in 0 until points.size - 1) {
        drawLine(
            color = Color.Black,
            start = points[i],
            end = points[i + 1],
            strokeWidth = STROKE_WIDTH_PX,
            cap = StrokeCap.Round,
        )
    }
}

private fun logStroke(
    strokeIndex: Int,
    points: List<Offset>,
) {
    Log.d(TAG, "Stroke $strokeIndex complete: ${points.size} points")
    points.forEachIndexed { index, point ->
        Log.d(TAG, "  [$index] x=${point.x}, y=${point.y}")
    }
}
