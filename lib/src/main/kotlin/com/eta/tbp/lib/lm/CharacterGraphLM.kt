package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.memory.GraphMatcher
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.edgeChainOf
import kotlin.math.atan2

/**
 * Tier 2: builds a character's graph from the primitive stream coming from
 * [PrimitiveLM] and matches it against every previously-taught
 * [GraphObjectModel] in [memory].
 *
 * "Episode" is one stroke, same scoping as [PrimitiveLM] — genuinely
 * multi-stroke characters are an explicitly deferred problem (see
 * IMPLEMENTATION_PLAN.md's own "least theoretically settled" caveat), not
 * something this phase solves.
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

        // PrimitiveLM's relativeAngle is a turn-from-previous encoding; summing it
        // back up reconstructs the same absolute-angle sequence PrimitiveLM itself
        // tracked internally (see PrimitiveLM.emitPrimitive).
        runningAbsoluteAngle += relativeAngle

        return GraphNode(
            id = nodeBuffer.size,
            location = location.copyOf(),
            absoluteAngle = runningAbsoluteAngle,
            primitiveType = features.type,
        )
    }
}
