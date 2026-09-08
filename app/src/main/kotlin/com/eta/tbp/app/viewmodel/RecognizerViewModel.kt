package com.eta.tbp.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import com.eta.tbp.app.sensor.toRawPoints
import com.eta.tbp.lib.lm.CharacterGraphLM
import com.eta.tbp.lib.lm.PrimitiveGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.lm.recognitionResult
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.orchestrator.MontyOrchestrator
import com.eta.tbp.lib.orchestrator.PrimitiveOverlay

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
 *
 * [TeachMode.PRIMITIVES] is Tier 1 teaching, wired directly against
 * [primitiveGraphLM] rather than through [orchestrator] — the orchestrator
 * exists to coordinate segmentation search during a character episode,
 * which teaching one primitive in isolation has nothing to do with; its
 * own [MontyOrchestrator.teach] is hard-wired to the character tier only.
 * [TeachMode.CHARACTERS] is gated behind [taughtPrimitiveLabels] being
 * non-empty — the "shapes before letters" curriculum, enforced both in the
 * UI (the mode switch) and here (see [onStrokeCompleted]'s guard) since a
 * character taught before any primitive exists would build a
 * [com.eta.tbp.lib.memory.GraphObjectModel] out of nothing but
 * `"unknown"`-labeled nodes, permanently poisoning [memory].
 */
class RecognizerViewModel : ViewModel() {
    private val memory = GraphMemory()
    private val primitiveGraphLM = PrimitiveGraphLM()
    private val tier2 = CharacterGraphLM(lmId = "character-0", memory = memory)
    private val orchestrator = MontyOrchestrator(primitiveGraphLM, tier2)

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
    var currentPrimitives by mutableStateOf<List<GraphNode>>(emptyList())
        private set

    /** Same primitives as [currentPrimitives], as on-canvas bounding boxes — [MontyOrchestrator.currentPrimitiveOverlays], for the canvas overlay. */
    var primitiveOverlays by mutableStateOf<List<PrimitiveOverlay>>(emptyList())
        private set

    /** Every learned graph, by label — [GraphMemory.snapshot], for the LM-state overlay. */
    var learnedGraphs by mutableStateOf<Map<String, List<GraphObjectModel>>>(emptyMap())
        private set

    /** Whether the LM-state overlay is showing. Persists across characters once opened. */
    var showLmState by mutableStateOf(false)
        private set

    /** Which teaching mode is active. Defaults to primitives — nothing can be recognized until at least one exists. */
    var mode by mutableStateOf(TeachMode.PRIMITIVES)
        private set

    /** The candidate primitive drawn so far this round; null while nothing's drawn yet. */
    var primitiveStroke by mutableStateOf<List<Offset>?>(null)
        private set

    /** [primitiveStroke]'s evidence against every already-taught label — computed once the stroke completes. */
    var primitiveEvidence by mutableStateOf<Map<String, Float>>(emptyMap())
        private set

    /** [primitiveEvidence] reduced to the same three-way decision [result] uses for characters — null while nothing's drawn yet. */
    var primitiveResult by mutableStateOf<RecognitionResult?>(null)
        private set

    /** Set once a primitive's been taught this round; cleared by the next stroke. */
    var taughtPrimitiveLabel by mutableStateOf<String?>(null)
        private set

    /** Every primitive label taught so far — gates [TeachMode.CHARACTERS] and shown in the LM-state overlay. */
    var taughtPrimitiveLabels by mutableStateOf<Set<String>>(emptySet())
        private set

    fun onSelectMode(newMode: TeachMode) {
        if (newMode == TeachMode.CHARACTERS && taughtPrimitiveLabels.isEmpty()) return
        mode = newMode
    }

    fun onPrimitiveStrokeCompleted(points: List<Offset>) {
        if (primitiveStroke != null) return
        primitiveStroke = points
        primitiveEvidence = primitiveGraphLM.evaluate(points.toRawPoints())
        primitiveResult = recognitionResult(primitiveEvidence)
        taughtPrimitiveLabel = null
    }

    fun onPrimitiveRedo() {
        primitiveStroke = null
        primitiveEvidence = emptyMap()
        primitiveResult = null
    }

    fun onTeachPrimitive(label: String) {
        val stroke = primitiveStroke ?: return
        if (label.isBlank()) return
        primitiveGraphLM.teach(label, stroke.toRawPoints())
        taughtPrimitiveLabel = label
        primitiveStroke = null
        primitiveEvidence = emptyMap()
        primitiveResult = null
        refreshLmState() // also updates taughtPrimitiveLabels
    }

    fun onConfirmPrimitive() {
        val recognized = primitiveResult as? RecognitionResult.Recognized ?: return
        onTeachPrimitive(recognized.label)
    }

    fun onCorrectPrimitive(label: String) = onTeachPrimitive(label)

    fun onStrokeCompleted(points: List<Offset>) {
        if (result != null || taughtPrimitiveLabels.isEmpty()) return
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
        currentPrimitives = tier2.currentNodes()
        primitiveOverlays = orchestrator.currentPrimitiveOverlays()
        learnedGraphs = memory.snapshot()
        taughtPrimitiveLabels = primitiveGraphLM.allLabels()
    }
}

/** Which tier is currently being taught — see the class doc for the "shapes before letters" gate this drives. */
enum class TeachMode { PRIMITIVES, CHARACTERS }
