package com.eta.tbp.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.eta.tbp.app.sensor.toRawPoints
import com.eta.tbp.lib.lm.CharacterGraphLM
import com.eta.tbp.lib.lm.PrimitiveLM
import com.eta.tbp.lib.lm.PrimitiveType
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.orchestrator.MontyOrchestrator
import com.eta.tbp.lib.sensor.TouchSensorModule

/**
 * Owns the one long-lived [MontyOrchestrator] (and the brain state behind
 * it) for the process lifetime — surviving configuration changes, unlike
 * plain `remember` state. Phase 7 adds real persistence across process
 * death; until then, teaching is only remembered for this process's life.
 *
 * Uses Compose `mutableStateOf` rather than `StateFlow`: every state change
 * here is synchronous and UI-event-driven (a stroke completing, a button
 * press), so there's no async/multicast need `StateFlow` would justify, and
 * this matches the rest of the app's existing `remember`-based Compose
 * style — just with the state hoisted to a configuration-change-surviving
 * owner instead of a `Composable`'s own memory.
 */
class RecognizerViewModel : ViewModel() {
    private val memory = GraphMemory()
    private val sensorModule = TouchSensorModule(sensorId = "touch-0")
    private val tier1 = PrimitiveLM(lmId = "primitive-0")
    private val tier2 = CharacterGraphLM(lmId = "character-0", memory = memory)
    private val orchestrator = MontyOrchestrator(sensorModule, tier1, tier2)

    /** Strokes drawn so far in the current character, for Compose rendering only. */
    var strokes by mutableStateOf<List<List<Offset>>>(emptyList())
        private set

    /** Live per-label match evidence, for the evidence bars. */
    var evidence by mutableStateOf<Map<String, Float>>(emptyMap())
        private set

    /** Set once "Done" is pressed; null while still drawing the current character. */
    var result by mutableStateOf<RecognitionResult?>(null)
        private set

    /** Set once a label has been committed (taught/confirmed/corrected) for the current character, until "Next". */
    var taughtLabel by mutableStateOf<String?>(null)
        private set

    /** Primitives Tier 1 has segmented so far this episode — [CharacterGraphLM.currentNodes], for the LM-state overlay. */
    var currentPrimitives by mutableStateOf<List<PrimitiveType>>(emptyList())
        private set

    /** Every learned graph, by label — [GraphMemory.snapshot], for the LM-state overlay. */
    var learnedGraphs by mutableStateOf<Map<String, List<GraphObjectModel>>>(emptyMap())
        private set

    /** Whether the LM-state overlay is showing. Persists across characters once opened. */
    var showLmState by mutableStateOf(false)
        private set

    fun onStrokeCompleted(points: List<Offset>) {
        if (result != null) return
        orchestrator.stepStroke(points.toRawPoints())
        strokes = strokes + listOf(points)
        refreshLmState()
    }

    fun onUndo() {
        if (strokes.isEmpty() || result != null) return
        orchestrator.undoLastStroke()
        strokes = strokes.dropLast(1)
        refreshLmState()
    }

    fun onClear() {
        if (strokes.isEmpty() || result != null) return
        orchestrator.clearCharacter()
        strokes = emptyList()
        refreshLmState()
    }

    fun onDone() {
        if (strokes.isEmpty() || result != null) return
        result = orchestrator.endCharacter()
    }

    fun onTeach(label: String) {
        if (label.isBlank()) return
        orchestrator.teach(label)
        taughtLabel = label
        refreshLmState()
    }

    fun onConfirm() {
        val recognized = result as? RecognitionResult.Recognized ?: return
        orchestrator.teach(recognized.label)
        taughtLabel = recognized.label
        refreshLmState()
    }

    fun onCorrect(label: String) = onTeach(label)

    fun onNext() {
        orchestrator.beginCharacter()
        strokes = emptyList()
        evidence = emptyMap()
        result = null
        taughtLabel = null
        refreshLmState()
    }

    fun onToggleLmState() {
        showLmState = !showLmState
    }

    private fun refreshLmState() {
        evidence = tier2.evidenceSnapshot()
        currentPrimitives = tier2.currentNodes().map { it.primitiveType }
        learnedGraphs = memory.snapshot()
    }
}
