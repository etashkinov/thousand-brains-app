package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.memory.GraphMatcher
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.edgeChainOf
import com.eta.tbp.lib.sensor.PrimitiveFeatures
import kotlin.math.atan2

/**
 * Tier 2: builds a character's graph from the primitive stream coming from
 * [com.eta.tbp.lib.sensor.PrimitiveSensorModule] and matches it against
 * every previously-taught [GraphObjectModel] in [memory].
 *
 * "Episode" is one full character, same scoping as
 * [com.eta.tbp.lib.sensor.PrimitiveSensorModule] — uniform across every
 * stage of the pipeline, matching real Monty's own episode boundary (see
 * IMPLEMENTATION_PLAN.md §3.6). A character may span several strokes:
 * [nodeBuffer] simply accumulates whatever primitives arrive between
 * [preEpisode] and [postEpisode], regardless of how many strokes that
 * spans — genuine cross-stroke compositional generalization is still an
 * explicitly deferred problem (see IMPLEMENTATION_PLAN.md's own "least
 * theoretically settled" caveat), but multi-stroke accumulation itself is
 * not.
 *
 * Evidence accumulates live as primitives arrive (not just once the stroke
 * completes): each new primitive extends this episode's node buffer, and
 * [GraphMatcher.partialMatchScore] scores that growing shape against every
 * stored model's same-length window.
 *
 * [getOutput] mirrors real Monty's `get_output()`: a single-hypothesis
 * point estimate (the current best label + its confidence), structurally
 * identical to what a SensorModule would emit — never a full evidence map.
 * Monty puts multi-hypothesis evidence in an entirely separate mechanism
 * (`send_out_vote()`'s dict keyed by object id, not a `Message` at all),
 * not inside the uniform feed-forward message. The full evidence-by-label
 * breakdown this app's UI needs (live bars, tie detection) is exposed via
 * [evidenceSnapshot] instead — a direct query a caller makes, the same way
 * Monty's own logging/experiment harness reads an LM's internal hypothesis
 * state directly rather than through a `Message`.
 *
 * Teaching is deliberately *not* part of the [LearningModule] interface:
 * Monty's own framework gets ground-truth labels from a labeled dataset,
 * but this app's v1 design is a human teaching by drawing + labeling, so
 * [teach] is this app's honest equivalent of that ground truth, not a
 * deviation from the port.
 */
class CharacterGraphLM(
    override val lmId: String,
    private val memory: GraphMemory,
) : LearningModule<Map<String, List<GraphObjectModel>>> {
    private val nodeBuffer = mutableListOf<GraphNode>()
    private var runningAbsoluteAngle = 0f
    private var lastCompletedNodes: List<GraphNode>? = null

    override fun matchingStep(messages: List<CmpMessage>) {
        for (message in messages) {
            if (!message.passMessage) continue
            nodeBuffer.add(toGraphNode(message))
        }
    }

    override fun receiveVotes(votes: List<Any>) {
        // No-op in v1: a single LM per tier, nothing to cross-check yet.
    }

    override fun sendOutVote(): Any? = null // No siblings to vote with in v1.

    override fun getOutput(): CmpMessage? {
        val topEntry = evidenceSnapshot().maxByOrNull { it.value } ?: return null
        return CmpMessage(
            location = null,
            morphologicalFeatures = null,
            nonMorphologicalFeatures = topEntry.key,
            confidence = topEntry.value,
            passMessage = true,
            senderId = lmId,
            senderType = SenderType.LM,
            processFeaturesInLm = true,
        )
    }

    /**
     * The full current evidence breakdown across every taught label — for
     * direct introspection (UI live-evidence bars, tie detection), not
     * something the CMP message carries. Empty if nothing's been observed
     * yet this episode or nothing's been taught.
     */
    fun evidenceSnapshot(): Map<String, Float> {
        if (nodeBuffer.isEmpty()) return emptyMap()
        return memory.allLabels().associateWith { label ->
            memory.candidatesForLabel(label).maxOf { stored -> GraphMatcher.partialMatchScore(stored, nodeBuffer) }
        }
    }

    /**
     * The primitives buffered so far this episode — direct introspection
     * for debugging/inspection (e.g. an LM-state overlay), same spirit as
     * [evidenceSnapshot]: a plain query a caller makes, not something a CMP
     * message carries. Empty before any stroke completes or right after
     * [preEpisode].
     */
    fun currentNodes(): List<GraphNode> = nodeBuffer.toList()

    override fun preEpisode() {
        nodeBuffer.clear()
        runningAbsoluteAngle = 0f
    }

    override fun postEpisode() {
        if (nodeBuffer.isNotEmpty()) {
            lastCompletedNodes = nodeBuffer.toList()
        }
    }

    override fun setExperimentMode(mode: ExperimentMode) {
        // No behavioral difference yet: v1 has no training-only bookkeeping.
    }

    override fun state(): Map<String, List<GraphObjectModel>> = memory.snapshot()

    override fun loadState(state: Map<String, List<GraphObjectModel>>) = memory.restore(state)

    /** Labels the most recently completed drawing and folds it into memory. No-op before any stroke completes. */
    fun teach(label: String) {
        val nodes = lastCompletedNodes ?: return
        memory.addOrMerge(GraphObjectModel(label, nodes, edgeChainOf(nodes), exemplarCount = 1), label)
    }

    /**
     * Labels within [xPercentThreshold]% of the max evidence — a simplified
     * port of Monty's `get_possible_matches()`/`_threshold_possible_matches()`
     * (`evidence_matching/learning_module.py`): straight percent-of-max
     * thresholding, dropping Monty's own mean/std branching and its
     * `len(graph_memory) == 1` special case (not needed at this evidence
     * scale — same simplification spirit as [GraphMemory.detectNewObject]
     * vs. Monty's k-steps/exponential version). Strict `>` matches Monty's
     * own `ge > th` exactly. 0 results means no match (Monty's "no_match"
     * terminal state), 1 a confident recognition, 2+ a genuine tie.
     */
    fun possibleMatches(xPercentThreshold: Float = 10f): List<String> {
        val evidence = evidenceSnapshot()
        if (evidence.isEmpty()) return emptyList()
        val maxEvidence = evidence.values.max()
        val threshold = if (maxEvidence > 0f) maxEvidence - (maxEvidence * xPercentThreshold / 100f) else 0f
        return evidence.filter { it.value > threshold }.keys.toList()
    }

    /** Combines [possibleMatches] and [evidenceSnapshot] into the three-way UI decision. */
    fun recognitionResult(): RecognitionResult {
        val matches = possibleMatches()
        val evidence = evidenceSnapshot()
        return when (matches.size) {
            0 -> RecognitionResult.Unknown
            1 -> RecognitionResult.Recognized(matches.single(), evidence.getValue(matches.single()))
            else -> RecognitionResult.Ambiguous(matches, evidence)
        }
    }

    private fun toGraphNode(message: CmpMessage): GraphNode {
        val features = message.nonMorphologicalFeatures
        if (features !is PrimitiveFeatures) {
            throw IllegalArgumentException(
                "CharacterGraphLM messages must carry PrimitiveFeatures as nonMorphologicalFeatures. " +
                    "Found: ${features::class.simpleName}",
            )
        }
        val location = requireNotNull(message.location) { "Primitive messages must carry a location" }
        val poseVectors = requireNotNull(message.getPoseVectors()) { "Primitive messages must carry a pose" }
        val relativeAngle = atan2(poseVectors[0][1], poseVectors[0][0])

        // PrimitiveSensorModule's relativeAngle is a turn-from-previous encoding;
        // summing it back up reconstructs the same absolute-angle sequence
        // PrimitiveSensorModule itself tracked internally (see its emitPrimitive).
        runningAbsoluteAngle += relativeAngle

        return GraphNode(
            id = nodeBuffer.size,
            location = location.copyOf(),
            absoluteAngle = runningAbsoluteAngle,
            measurement = features.measurement,
        )
    }
}

/**
 * The three-way outcome the teach/recognize UI branches on. Mirrors
 * Monty's own `possible_matches`-driven terminal states
 * (`evidence_matching/learning_module.py`), not an app-invented concept:
 * zero possible matches is Monty's own "no_match" terminal state, one is a
 * normal convergence, and 2+ is Monty's own multi-hypothesis case — this
 * app surfaces that last case as a question instead of forcing a guess.
 */
sealed class RecognitionResult {
    object Unknown : RecognitionResult()

    data class Recognized(
        val label: String,
        val confidence: Float,
    ) : RecognitionResult()

    data class Ambiguous(
        val labels: List<String>,
        val evidence: Map<String, Float>,
    ) : RecognitionResult()
}
