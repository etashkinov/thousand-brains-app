package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.memory.EvidenceFeature
import com.eta.tbp.lib.memory.GraphMatcher
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.edgeChainOf
import kotlin.math.atan2

/**
 * Tier 2: builds a character's graph from the primitive stream coming from
 * [com.eta.tbp.lib.sensor.PrimitiveSensorModule] and matches it against
 * every previously-taught [GraphObjectModel] in [memory]. Generic over the
 * node feature payload [F] — real Monty's own `EvidenceGraphLM` is one class
 * reused at every hierarchy level via constructor config, not type
 * branching; this class mirrors that shape. Today's app still has exactly
 * one real instantiation (`F = `[com.eta.tbp.lib.sensor.PrimitiveMeasurement],
 * wired up in `RecognizerViewModel`) — [com.eta.tbp.lib.lm.PrimitiveGraphLM]
 * (Tier 1) deliberately stays a separate, non-generic class; see
 * IMPLEMENTATION_PLAN.md's "why aren't PrimitiveGraphLM and (this class) one
 * shared EvidenceGraphLM" entry for why that merge was rejected and why
 * genericizing this class alone doesn't reopen it.
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
class EvidenceGraphLM<F : EvidenceFeature<F>>(
    override val lmId: String,
    private val memory: GraphMemory<F>,
    private val featureOf: (CmpMessage) -> F,
) : LearningModule<Map<String, List<GraphObjectModel<F>>>> {
    private val nodeBuffer = mutableListOf<GraphNode<F>>()
    private var runningAbsoluteAngle = 0f
    private var lastCompletedNodes: List<GraphNode<F>>? = null

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
    fun currentNodes(): List<GraphNode<F>> = nodeBuffer.toList()

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

    override fun state(): Map<String, List<GraphObjectModel<F>>> = memory.snapshot()

    override fun loadState(state: Map<String, List<GraphObjectModel<F>>>) = memory.restore(state)

    /** Labels the most recently completed drawing and folds it into memory. No-op before any stroke completes. */
    fun teach(label: String) {
        val nodes = lastCompletedNodes ?: return
        memory.addOrMerge(GraphObjectModel(label, nodes, edgeChainOf(nodes), exemplarCount = 1), label)
    }

    /** Labels within [xPercentThreshold]% of the max evidence — see the top-level [possibleMatches] this delegates to. */
    fun possibleMatches(xPercentThreshold: Float = 10f): List<String> = possibleMatches(evidenceSnapshot(), xPercentThreshold)

    /** Combines [possibleMatches] and [evidenceSnapshot] into the three-way UI decision — see the top-level [recognitionResult]. */
    fun recognitionResult(): RecognitionResult = recognitionResult(evidenceSnapshot())

    private fun toGraphNode(message: CmpMessage): GraphNode<F> {
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
            feature = featureOf(message),
        )
    }
}
