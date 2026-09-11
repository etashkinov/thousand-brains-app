package com.eta.tbp.app.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eta.tbp.app.capture.CaptureMode
import com.eta.tbp.app.capture.CapturedExample
import com.eta.tbp.app.sensor.toRawPoints
import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.PrimitiveGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.lm.recognitionResult
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.orchestrator.MontyOrchestrator
import com.eta.tbp.lib.orchestrator.PrimitiveOverlay
import com.eta.tbp.lib.sensor.PrimitiveFeatures
import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the one long-lived [MontyOrchestrator] (and the brain state behind
 * it) for the process lifetime — surviving configuration changes, unlike
 * plain `remember` state. Phase 7 adds real persistence across process
 * death; until then, teaching is only remembered for this process's life.
 *
 * Uses Compose `mutableStateOf` for UI-facing fields, but every touch of
 * [orchestrator]/[tier2]/[primitiveGraphLM]/[memory] is confined to
 * [matchingDispatcher] — a single serial background dispatcher, never Main.
 * These `lib` objects are plain, non-thread-safe mutable Kotlin classes
 * (`EvidenceGraphLM`'s private node buffer, `GraphMemory`'s private map,
 * etc.); as taught templates/characters accumulate, matching against them
 * takes long enough to visibly freeze the UI if run on Main, which is what
 * every method here used to do synchronously. Retrofitting thread-safety
 * into `lib` itself would be a bigger, more invasive change than confining
 * every touch to one thread from this side — `lib` stays the plain,
 * synchronous, deterministic module it's designed to be, and never knows
 * its caller is async.
 *
 * [matchingDispatcher] is `Dispatchers.Default.limitedParallelism(1)`, not
 * a dedicated single-thread executor — no OS thread to leak/close, and its
 * internal queue is strictly FIFO (bounded to one active worker at a time)
 * *relative to actual dispatch order*. That guarantee only holds because
 * every dispatch here originates from a [viewModelScope] coroutine started
 * on `Dispatchers.Main.immediate` from an already-Main-thread Compose
 * callback — `Main.immediate` runs synchronously (no re-dispatch) in that
 * case, so submission order to [matchingDispatcher] == the order the user
 * actually triggered these actions in. This would quietly stop being true
 * if some method here were ever called from a non-Main coroutine.
 *
 * Every method that touches `lib` state bundles its mutation *and* its
 * subsequent read into one [withContext] block (see [captureLmState]) —
 * never a mutation in one dispatch and a read in a separately-dispatched
 * one. That distinction matters: if two user actions race close together
 * (e.g. a stroke completes, then "Undo" is tapped before the first
 * stroke's matching has finished), both correctly serialize *relative to
 * each other* on [matchingDispatcher] — but a separately-dispatched read
 * for the first action could still run on Main concurrently with the
 * second action's already-started background mutation, a genuine data
 * race on `lib`'s mutable state. Bundling read with write makes each
 * user action one atomic hand-off to the serial dispatcher.
 *
 * That "serial log of additive operations" model has one exception:
 * [MontyOrchestrator.endCharacter] is a phase transition, not another log
 * entry (its own doc: `postEpisode()`'s real effect is deliberately
 * reserved for it, never fired on a plain `replay`). [isEndingCharacter]
 * is a synchronous guard flipped the instant "Done" is pressed, before
 * dispatch, so a queued Undo/Clear/stroke can't retroactively mutate an
 * episode already being finalized — see [onDone].
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
    private val memory = GraphMemory<PrimitiveMeasurement>()
    private val primitiveGraphLM = PrimitiveGraphLM()
    private val tier2 =
        EvidenceGraphLM(
            lmId = "character-0",
            memory = memory,
            featureOf = { message ->
                val features = message.nonMorphologicalFeatures
                if (features !is PrimitiveFeatures) {
                    throw IllegalArgumentException(
                        "RecognizerViewModel's EvidenceGraphLM<PrimitiveMeasurement> requires PrimitiveFeatures. " +
                            "Found: ${features::class.simpleName}",
                    )
                }
                features.measurement
            },
        )
    private val orchestrator = MontyOrchestrator(primitiveGraphLM, tier2)
    private val matchingDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Strokes drawn so far in the current character, for Compose rendering only. */
    var strokes by mutableStateOf<List<List<Offset>>>(emptyList())
        private set

    /** Live per-label match evidence, for the evidence bars. */
    var evidence by mutableStateOf<Map<String, Float>>(emptyMap())
        private set

    /** Set once "Done" is pressed and its background matching completes; null while still drawing or ending. */
    var result by mutableStateOf<RecognitionResult?>(null)
        private set

    /** Set synchronously the instant "Done" is pressed, before its async round trip — see the class doc. */
    var isEndingCharacter by mutableStateOf(false)
        private set

    /** Set once a label has been committed (taught/confirmed/corrected) for the current character, until "Next". */
    var taughtLabel by mutableStateOf<String?>(null)
        private set

    /** Primitives Tier 1 has segmented so far this episode — [EvidenceGraphLM.currentNodes], for the LM-state overlay. */
    var currentPrimitives by mutableStateOf<List<GraphNode<PrimitiveMeasurement>>>(emptyList())
        private set

    /** Same primitives as [currentPrimitives], as on-canvas bounding boxes — [MontyOrchestrator.currentPrimitiveOverlays], for the canvas overlay. */
    var primitiveOverlays by mutableStateOf<List<PrimitiveOverlay>>(emptyList())
        private set

    /** Every learned graph, by label — [GraphMemory.snapshot], for the LM-state overlay. */
    var learnedGraphs by mutableStateOf<Map<String, List<GraphObjectModel<PrimitiveMeasurement>>>>(emptyMap())
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

    /** Whether taught examples are being recorded for later export — off by default; this records the user's own handwriting, so it's opt-in, not silent. */
    var captureEnabled by mutableStateOf(false)
        private set

    /** Examples recorded since capture was turned on (or since the last export) — see [onCapturedExamplesExported]. */
    var capturedExamples by mutableStateOf<List<CapturedExample>>(emptyList())
        private set

    fun onSelectMode(newMode: TeachMode) {
        if (newMode == TeachMode.CHARACTERS && taughtPrimitiveLabels.isEmpty()) return
        mode = newMode
    }

    fun onPrimitiveStrokeCompleted(points: List<Offset>) {
        if (primitiveStroke != null) return
        primitiveStroke = points
        taughtPrimitiveLabel = null
        viewModelScope.launch {
            val newEvidence = withContext(matchingDispatcher) { primitiveGraphLM.evaluate(points.toRawPoints()) }
            primitiveEvidence = newEvidence
            primitiveResult = recognitionResult(newEvidence)
        }
    }

    fun onPrimitiveRedo() {
        primitiveStroke = null
        primitiveEvidence = emptyMap()
        primitiveResult = null
    }

    fun onTeachPrimitive(label: String) {
        val stroke = primitiveStroke ?: return
        if (label.isBlank()) return
        captureIfEnabled(CaptureMode.PRIMITIVE, label, listOf(stroke))
        taughtPrimitiveLabel = label
        primitiveStroke = null
        primitiveEvidence = emptyMap()
        primitiveResult = null
        viewModelScope.launch {
            val labels =
                withContext(matchingDispatcher) {
                    primitiveGraphLM.teach(label, stroke.toRawPoints())
                    primitiveGraphLM.allLabels()
                }
            taughtPrimitiveLabels = labels
        }
    }

    fun onConfirmPrimitive() {
        val recognized = primitiveResult as? RecognitionResult.Recognized ?: return
        onTeachPrimitive(recognized.label)
    }

    fun onCorrectPrimitive(label: String) = onTeachPrimitive(label)

    fun onStrokeCompleted(points: List<Offset>) {
        if (result != null || isEndingCharacter || taughtPrimitiveLabels.isEmpty()) return
        strokes = strokes + listOf(points)
        viewModelScope.launch {
            val snapshot =
                withContext(matchingDispatcher) {
                    orchestrator.stepStroke(points.toRawPoints())
                    captureLmState()
                }
            applyLmState(snapshot)
        }
    }

    fun onUndo() {
        if (strokes.isEmpty() || result != null || isEndingCharacter) return
        strokes = strokes.dropLast(1)
        viewModelScope.launch {
            val snapshot =
                withContext(matchingDispatcher) {
                    orchestrator.undoLastStroke()
                    captureLmState()
                }
            applyLmState(snapshot)
        }
    }

    fun onClear() {
        if (strokes.isEmpty() || result != null || isEndingCharacter) return
        strokes = emptyList()
        viewModelScope.launch {
            val snapshot =
                withContext(matchingDispatcher) {
                    orchestrator.clearCharacter()
                    captureLmState()
                }
            applyLmState(snapshot)
        }
    }

    fun onDone() {
        if (strokes.isEmpty() || result != null || isEndingCharacter) return
        isEndingCharacter = true
        viewModelScope.launch {
            val newResult = withContext(matchingDispatcher) { orchestrator.endCharacter() }
            result = newResult
            isEndingCharacter = false
        }
    }

    fun onTeach(label: String) {
        if (label.isBlank() || taughtLabel != null) return
        captureIfEnabled(CaptureMode.CHARACTER, label, strokes)
        taughtLabel = label
        viewModelScope.launch {
            val snapshot =
                withContext(matchingDispatcher) {
                    orchestrator.teach(label)
                    captureLmState()
                }
            applyLmState(snapshot)
        }
    }

    fun onConfirm() {
        val recognized = result as? RecognitionResult.Recognized ?: return
        onTeach(recognized.label)
    }

    fun onCorrect(label: String) = onTeach(label)

    fun onNext() {
        result = null
        taughtLabel = null
        strokes = emptyList()
        evidence = emptyMap()
        viewModelScope.launch {
            val snapshot =
                withContext(matchingDispatcher) {
                    orchestrator.beginCharacter()
                    captureLmState()
                }
            applyLmState(snapshot)
        }
    }

    fun onToggleLmState() {
        showLmState = !showLmState
    }

    fun onToggleCapture() {
        captureEnabled = !captureEnabled
    }

    /** Clears the buffer after [com.eta.tbp.app.capture.exportCaptures] has handed it off to the share sheet. */
    fun onCapturedExamplesExported() {
        capturedExamples = emptyList()
    }

    /**
     * Records one taught example verbatim, if [captureEnabled] — see the
     * class doc's [taughtPrimitiveLabels] paragraph and IMPLEMENTATION_PLAN.md's
     * Phase 5.5a. Only touches plain Compose state (never `lib`), so unlike
     * every other mutation here, this runs synchronously on Main — no
     * [matchingDispatcher] involved.
     */
    private fun captureIfEnabled(
        mode: CaptureMode,
        label: String,
        strokes: List<List<Offset>>,
    ) {
        if (!captureEnabled) return
        capturedExamples =
            capturedExamples +
            CapturedExample(
                mode = mode,
                label = label,
                strokes = strokes.map { it.toRawPoints() },
                timestampMs = System.currentTimeMillis(),
            )
    }

    /** Everything [applyLmState] needs, captured in one [matchingDispatcher] pass — see the class doc for why bundling matters. */
    private data class LmSnapshot(
        val evidence: Map<String, Float>,
        val currentPrimitives: List<GraphNode<PrimitiveMeasurement>>,
        val primitiveOverlays: List<PrimitiveOverlay>,
        val learnedGraphs: Map<String, List<GraphObjectModel<PrimitiveMeasurement>>>,
        val taughtPrimitiveLabels: Set<String>,
    )

    /** Must only ever be called from within a [matchingDispatcher] block — reads `lib` state directly. */
    private fun captureLmState(): LmSnapshot =
        LmSnapshot(
            evidence = tier2.evidenceSnapshot(),
            currentPrimitives = tier2.currentNodes(),
            primitiveOverlays = orchestrator.currentPrimitiveOverlays(),
            learnedGraphs = memory.snapshot(),
            taughtPrimitiveLabels = primitiveGraphLM.allLabels(),
        )

    /** Applies a snapshot captured via [captureLmState] to Compose state. Safe to call from Main. */
    private fun applyLmState(snapshot: LmSnapshot) {
        evidence = snapshot.evidence
        currentPrimitives = snapshot.currentPrimitives
        primitiveOverlays = snapshot.primitiveOverlays
        learnedGraphs = snapshot.learnedGraphs
        taughtPrimitiveLabels = snapshot.taughtPrimitiveLabels
    }
}

/** Which tier is currently being taught — see the class doc for the "shapes before letters" gate this drives. */
enum class TeachMode { PRIMITIVES, CHARACTERS }
