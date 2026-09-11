package com.eta.tbp.app.capture

import com.eta.tbp.lib.sensor.RawPoint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeCaptureTest {
    @Test
    fun `toCaptureJson round-trips mode, label, timestamp, and every point`() {
        val examples =
            listOf(
                CapturedExample(
                    mode = CaptureMode.CHARACTER,
                    label = "A",
                    strokes = listOf(listOf(RawPoint(1f, 2f), RawPoint(3f, 4f)), listOf(RawPoint(5f, 6f))),
                    timestampMs = 1_234_567_890L,
                ),
                CapturedExample(
                    mode = CaptureMode.PRIMITIVE,
                    label = "line",
                    strokes = listOf(listOf(RawPoint(0f, 0f))),
                    timestampMs = 42L,
                ),
            )

        val root = JSONObject(examples.toCaptureJson())

        assertEquals(1, root.getInt("schemaVersion"))
        val captures = root.getJSONArray("captures")
        assertEquals(2, captures.length())

        val first = captures.getJSONObject(0)
        assertEquals("CHARACTER", first.getString("mode"))
        assertEquals("A", first.getString("label"))
        assertEquals(1_234_567_890L, first.getLong("timestampMs"))
        val firstStrokes = first.getJSONArray("strokes")
        assertEquals(2, firstStrokes.length())
        val firstStrokeFirstPoint = firstStrokes.getJSONArray(0).getJSONObject(0)
        assertEquals(1.0, firstStrokeFirstPoint.getDouble("x"), 1e-6)
        assertEquals(2.0, firstStrokeFirstPoint.getDouble("y"), 1e-6)
        val firstStrokeSecondPoint = firstStrokes.getJSONArray(0).getJSONObject(1)
        assertEquals(3.0, firstStrokeSecondPoint.getDouble("x"), 1e-6)
        assertEquals(4.0, firstStrokeSecondPoint.getDouble("y"), 1e-6)
        val secondStrokeOnlyPoint = firstStrokes.getJSONArray(1).getJSONObject(0)
        assertEquals(5.0, secondStrokeOnlyPoint.getDouble("x"), 1e-6)
        assertEquals(6.0, secondStrokeOnlyPoint.getDouble("y"), 1e-6)

        val second = captures.getJSONObject(1)
        assertEquals("PRIMITIVE", second.getString("mode"))
        assertEquals("line", second.getString("label"))
        assertEquals(42L, second.getLong("timestampMs"))
    }

    @Test
    fun `toCaptureJson on an empty list still has a valid schema`() {
        val root = JSONObject(emptyList<CapturedExample>().toCaptureJson())

        assertEquals(1, root.getInt("schemaVersion"))
        assertEquals(0, root.getJSONArray("captures").length())
    }
}
