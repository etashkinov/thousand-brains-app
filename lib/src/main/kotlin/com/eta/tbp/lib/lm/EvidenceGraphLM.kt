package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.log.Logger
import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.GraphMatcher
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.memory.edgeChainOf

/**
 * Tier 2: builds a character's graph from the primitive stream coming from
 * [com.eta.tbp.lib.sensor.PrimitiveSensorModule] and matches it against
 * every previously-taught [GraphObjectModel] in [memory]. Generic over the
 * node feature payload [F] — real Monty's own `EvidenceGraphLM` is one class
 * reused at every hierarchy level via constructor config, not type
 * branching; this class mirrors that shape. Today's app still has exactly
 * one real instantiation (`F = `[com.eta.tbp.lib.sensor.PrimitiveFeature],
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
 * [nodeBuffer] itself keeps every observed node in strict visit order,
 * unconditionally — [teach] needs the *complete* sequence even when nothing
 * in it matched anything taught yet (that's exactly what teaching a
 * genuinely new object looks like). Matching is different: [GraphMatcher]
 * anchors on whichever node it's given first (see its own doc for why one
 * fixed node is enough), and a node with zero feature-compatible match
 * anywhere in a taught model dooms that model to zero evidence for the rest
 * of the episode. So [evidenceSnapshot] doesn't hand [GraphMatcher]
 * [nodeBuffer] as observed — it hands it [matchingCandidates]'s view, which
 * drops any node [GraphMemory.hasCompatibleFeature] doesn't recognize
 * anywhere (it could never help a score anyway) rather than letting it
 * monopolize the anchor slot or inflate the candidate count past a smaller
 * taught model's own node count. An automated explorer starting from a
 * random, unknown location ([com.eta.tbp.lib.city.CityExperiment]) can't
 * guarantee its first observation is a discriminating one; this is what
 * lets a later, recognized observation still anchor the match instead of
 * an earlier unrecognized one permanently stalling it.
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
 * deviation from the port. It's still gated on [ExperimentMode] exactly the
 * way real Monty's own learning is: `GraphLM.update_ltm_from_stm()`
 * (`evidence_matching/learning_module.py`) only calls `_update_memory()`
 * `if self.mode is ExperimentMode.TRAIN` — an EVALUATE-mode episode that
 * ends in no match writes nothing to memory, full stop, rather than
 * inventing a label for whatever wasn't recognized. [mode] defaults to
 * TRAIN so every existing direct caller (a human teaching by drawing, or a
 * test that never touches [setExperimentMode]) keeps working unchanged;
 * only a caller that explicitly asks for EVALUATE (e.g.
 * [com.eta.tbp.lib.city.CityExperiment.evaluate]) gets the stricter
 * behavior.
 *
 * [suggestNextLocation] is this class's own embedded Goal State Generator
 * (mirrors real Monty's `EvidenceGoalGenerator` — see [suggestGoalLocation]'s
 * doc): a third output channel alongside [getOutput]/[sendOutVote], not
 * folded into either, for whatever explores on this LM's behalf (e.g.
 * [com.eta.tbp.lib.city.CityExperiment]) to consult instead of choosing
 * blindly. Entirely a function of this LM's own state — [memory] and what
 * it's already observed/checked — never anything about the domain under
 * exploration itself, which is exactly what keeps it generic over any
 * [Location]/[Feature] pair rather than tied to one caller's domain.
 *
 * [logger] defaults to [Logger.None] (silent) so no existing caller/test is
 * affected by adding it — pass [Logger.Console], or an `app`-side
 * implementation, to see this class's own events (nodes matched, episode
 * boundaries, teach outcomes, goal suggestions).
 */
class EvidenceGraphLM(
    override val lmId: String,
    private val memory: GraphMemory,
    private val logger: Logger = Logger.Console,
) : LearningModule<Map<String, List<GraphObjectModel>>> {
    private val nodeBuffer = mutableListOf<GraphNode>()
    private val checkedLocations = mutableSetOf<Location>()
    private var lastCompletedNodes: List<GraphNode>? = null
    private var mode = ExperimentMode.TRAIN

    override fun matchingStep(messages: List<CmpMessage>) {
        for (message in messages) {
            message.location?.let { checkedLocations += it }
            if (!message.passMessage) continue
            val node = toGraphNode(message)
            nodeBuffer.add(node)
            logger.debug(TAG) { "[$lmId] node ${node.id}: '${node.feature.label}' at ${node.location}" }
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
            feature = null,
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
        val candidateNodes = matchingCandidates()
        return memory.allLabels().associateWith { label ->
            memory.candidatesForLabel(label).maxOf { stored -> GraphMatcher.partialMatchScore(stored, candidateNodes) }
        }
    }

    /**
     * [nodeBuffer] with any node [GraphMemory.hasCompatibleFeature] doesn't
     * recognize anywhere dropped — not just reordered out of the anchor
     * slot: a node nothing taught has ever seen the like of can't
     * contribute positively to *any* model's score (see class doc), so
     * leaving it in would only ever inflate [GraphMatcher.partialMatchScore]'s
     * candidate count past a smaller taught model's own node count, or trip
     * its per-node hard veto — never help. Removing it also happens to
     * promote the next recognized node into the anchor slot for free,
     * without needing a separate reordering step. Unchanged if [memory] has
     * nothing taught yet ([teach]ing a first-ever object needs every
     * observation kept), or if nothing in [nodeBuffer] is recognized at all
     * (falls back to the full, unfiltered buffer — evidence comes out at
     * zero regardless, via the same hard veto).
     */
    private fun matchingCandidates(): List<GraphNode> {
        if (memory.allLabels().isEmpty()) return nodeBuffer
        val recognized = nodeBuffer.filter { memory.hasCompatibleFeature(it.feature) }
        return recognized.ifEmpty { nodeBuffer }
    }

    /**
     * The primitives buffered so far this episode — direct introspection
     * for debugging/inspection (e.g. an LM-state overlay), same spirit as
     * [evidenceSnapshot]: a plain query a caller makes, not something a CMP
     * message carries. Empty before any stroke completes or right after
     * [preEpisode].
     */
    fun currentNodes(): List<GraphNode> = nodeBuffer.toList()

    /**
     * Where to look next to tell the currently tied hypotheses apart — see
     * [suggestGoalLocation]'s own doc, and real Monty's `propose_goals()`
     * pipeline this mirrors: a separate output channel from [getOutput],
     * not folded into its `CmpMessage` (real Monty keeps movement guidance
     * out of `get_output()`/`send_out_vote()` too). Null whenever there's
     * nothing to disambiguate — not [RecognitionResult.Ambiguous] yet, or
     * nothing left unchecked to suggest — in which case the caller should
     * fall back to its own default exploration policy (e.g. a random
     * unchecked location), the same way real Monty falls back to a naive
     * policy when goal-driven actions are off.
     */
    fun suggestNextLocation(): Location? {
        val result = recognitionResult()
        if (result !is RecognitionResult.Ambiguous) return null
        val suggestion = suggestGoalLocation(memory, result.labels, matchingCandidates(), checkedLocations)
        if (suggestion != null) {
            logger.debug(TAG) { "[$lmId] suggesting $suggestion to disambiguate ${result.labels}" }
        }
        return suggestion
    }

    override fun preEpisode() {
        nodeBuffer.clear()
        checkedLocations.clear()
        logger.debug(TAG) { "[$lmId] episode started" }
    }

    override fun postEpisode() {
        if (nodeBuffer.isNotEmpty()) {
            lastCompletedNodes = nodeBuffer.toList()
        }
        logger.debug(TAG) { "[$lmId] episode ended with ${nodeBuffer.size} node(s) observed" }
    }

    override fun setExperimentMode(mode: ExperimentMode) {
        this.mode = mode
        logger.debug(TAG) { "[$lmId] experiment mode set to $mode" }
    }

    override fun state(): Map<String, List<GraphObjectModel>> = memory.snapshot()

    override fun loadState(state: Map<String, List<GraphObjectModel>>) = memory.restore(state)

    /** Labels the most recently completed drawing and folds it into memory. No-op before any stroke completes, or outside [ExperimentMode.TRAIN] — see class doc. */
    fun teach(label: String) {
        if (mode != ExperimentMode.TRAIN) {
            logger.warn(TAG) { "[$lmId] teach('$label') ignored: experiment mode is $mode, not TRAIN" }
            return
        }
        val nodes =
            lastCompletedNodes ?: run {
                logger.warn(TAG) { "[$lmId] teach('$label') ignored: nothing observed yet this episode" }
                return
            }
        memory.addOrMerge(GraphObjectModel(label, nodes, edgeChainOf(nodes)), label)
        logger.info(TAG) { "[$lmId] taught '$label' from ${nodes.size} node(s)" }
    }

    /** Labels within [xPercentThreshold]% of the max evidence — see the top-level [possibleMatches] this delegates to. */
    fun possibleMatches(xPercentThreshold: Float = 10f): List<String> = possibleMatches(evidenceSnapshot(), xPercentThreshold)

    /** Combines [possibleMatches] and [evidenceSnapshot] into the three-way UI decision — see the top-level [recognitionResult]. */
    fun recognitionResult(): RecognitionResult = recognitionResult(evidenceSnapshot())

    private fun toGraphNode(message: CmpMessage): GraphNode {
        val location = requireNotNull(message.location) { "Messages fed into matchingStep must carry a location" }
        val feature = requireNotNull(message.feature) { "Messages fed into matchingStep must carry a feature" }
        return GraphNode(id = nodeBuffer.size, location = location, feature = feature)
    }

    private companion object {
        const val TAG = "EvidenceGraphLM"
    }
}
