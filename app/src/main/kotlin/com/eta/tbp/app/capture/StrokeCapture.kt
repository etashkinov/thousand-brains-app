package com.eta.tbp.app.capture

import com.eta.tbp.lib.sensor.RawPoint
import org.json.JSONArray
import org.json.JSONObject

/** Which tier a [CapturedExample] was taught to — mirrors [com.eta.tbp.app.viewmodel.TeachMode]. */
enum class CaptureMode { PRIMITIVE, CHARACTER }

/**
 * One taught example, captured verbatim at teach time (see
 * `RecognizerViewModel.captureIfEnabled`) for later use as a real-handwriting
 * calibration fixture — see IMPLEMENTATION_PLAN.md's Phase 5.5a/5.5b.
 * [strokes] are raw touch points, unnormalized, in on-screen coordinates —
 * exactly what `lib`'s `MontyOrchestrator.stepStroke()`/`PrimitiveGraphLM.teach()`
 * already consume, so a future `lib`-side fixture loader needs no reshaping.
 */
data class CapturedExample(
    val mode: CaptureMode,
    val label: String,
    val strokes: List<List<RawPoint>>,
    val timestampMs: Long,
)

private const val SCHEMA_VERSION = 1

/**
 * Serializes a batch of [CapturedExample]s to the export JSON schema:
 * `{"schemaVersion": 1, "captures": [{"mode", "label", "timestampMs", "strokes": [[{"x","y"}, ...], ...]}, ...]}`.
 * A top-level object (not a bare array) so the schema can grow later without
 * breaking exports already handed back for Phase 5.5b.
 */
fun List<CapturedExample>.toCaptureJson(): String {
    val captures = JSONArray()
    forEach { example ->
        val strokesJson = JSONArray()
        example.strokes.forEach { stroke ->
            val strokeJson = JSONArray()
            stroke.forEach { point ->
                strokeJson.put(
                    JSONObject().apply {
                        put("x", point.x.toDouble())
                        put("y", point.y.toDouble())
                    },
                )
            }
            strokesJson.put(strokeJson)
        }
        captures.put(
            JSONObject().apply {
                put("mode", example.mode.name)
                put("label", example.label)
                put("timestampMs", example.timestampMs)
                put("strokes", strokesJson)
            },
        )
    }
    val root =
        JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("captures", captures)
        }
    return root.toString(2)
}
