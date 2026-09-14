package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpGoal
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
 * stored model.
 *
 * [nodeBuffer] itself keeps every observed node in strict visit order,
 * unconditionally, and [evidenceSnapshot] hands [GraphMatcher] that same
 * buffer as-is, unfiltered — an earlier version of this class instead
 * pre-filtered it down to nodes [GraphMemory.hasCompatibleFeature]
 * recognized *somewhere*, on the reasoning that a node nothing taught has
 * ever seen the like of "can't contribute positively to any model's score."
 * That was wrong: dropping it meant a city with a landmark nothing taught
 * has ever seen (a real, discriminating fact — it doesn't belong to
 * whatever's being explored) simply vanished from the comparison instead of
 * counting against every candidate it doesn't fit, letting a handful of
 * genuinely matching nodes plus one utterly foreign one still read as a
 * confident, unique match. [GraphMatcher.partialMatchScore] fixes this at
 * the source (see its own doc) by scoring every observed node — one with no
 * match anywhere in a given candidate now counts as evidence *against* that
 * candidate specifically, the way real Monty's own evidence accumulation
 * treats an out-of-range observation, rather than being discarded before
 * comparison or vetoing every candidate outright.
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
 * [Experiment.evaluate]) gets the stricter
 * behavior.
 *
 * [proposeGoal] is this class's own embedded Goal State Generator (mirrors
 * real Monty's `EvidenceGoalGenerator` — see [suggestGoalLocation]'s doc): a
 * third output channel alongside [getOutput]/[sendOutVote], not folded into
 * either, for whatever explores on this LM's behalf (e.g. [Experiment]) to
 * consult instead of choosing blindly. Its result travels as a [CmpGoal] —
 * real Monty's own `propose_goals()` returns `Goal` CMP messages, not a bare
 * location, and [MotorSystem] is what actually consumes it. Entirely a
 * function of this LM's own state — [memory] and what it's already
 * observed/checked — never anything about the domain under exploration
 * itself, which is exactly what keeps it generic over any
 * [Location]/[Feature] pair rather than tied to one caller's domain.
 *
 * [logger] defaults to [Logger.None] (silent) so no existing caller/test is
 * affected by adding it — pass [Logger.Console], or an `app`-side
 * implementation, to see this class's own events (nodes matched, episode
 * boundaries, teach outcomes, goal suggestions).
 *
 * [positionTolerance] is this LM's one configured answer to "how close is
 * close enough to be the same place" — set once at construction rather
 * than threaded through every call that needs it, the same way real
 * Monty's `EvidenceGoalGenerator.__init__` takes `goal_tolerances` as
 * constructor config, not a `propose_goals()` argument
 * (`goal_generation.py`). [proposeGoal] uses it directly, and [Explorer]
 * reads this same property (rather than holding its own copy) when it
 * needs the identical notion of "same place" for its own
 * `visited`-location bookkeeping — one source of truth instead of every
 * caller along the chain repeating (and risking disagreeing on) the same
 * value. This LM owns the value, not
 * [com.eta.tbp.lib.sensor.Environment]: how forgiving a comparison should
 * be is a property of the learning logic doing the comparing, not of the
 * world being explored — see [com.eta.tbp.lib.sensor.Environment.featureAt]'s
 * own doc for the same point made from the environment side.
 */
class EvidenceGraphLM(
    override val lmId: String,
    val positionTolerance: Float = 0.3f,
    private val logger: Logger = Logger.Console,
) : LearningModule<Map<String, List<GraphObjectModel>>> {
    /**
     * Owned and constructed here, not injected — mirrors real Monty's
     * `EvidenceGraphLM.__init__` building its own `self.graph_memory`
     * (`evidence_matching/learning_module.py`). A prior version of this
     * class took a [GraphMemory] as a constructor parameter, built by
     * whatever wired the LM up; that only existed to work around
     * [Experiment] (then city-specific, `CityExperiment`) rebuilding its LM
     * on every episode, and let a caller reach in and share one mutable [GraphMemory]
     * across separate LM instances — [state]/[loadState] (already the
     * `LearningModule` contract's own checkpoint mechanism, matching
     * Monty's `state_dict` save/restore) is the correct way to move taught
     * knowledge between instances instead.
     */
    private val memory = GraphMemory()
    private val nodeBuffer = mutableListOf<GraphNode>()

    /**
     * Declared as `LinkedHashSet` specifically, not `mutableSetOf()`
     * (which returns one too, but as the widened `MutableSet` type — a
     * caller reading that declaration has no way to tell iteration order
     * means anything): `LinkedHashSet`'s own contract guarantees iteration
     * order equals insertion order, so this one field serves both
     * [checkedLocations] (fast membership testing — order doesn't matter)
     * and [checkedLocationsInOrder] (the path this episode actually took —
     * order is the entire point) with nothing to keep in sync.
     */
    private val checkedLocations = LinkedHashSet<Location>()
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
        return memory.allLabels().associateWith { label ->
            memory.candidatesForLabel(label).maxOf { stored -> GraphMatcher.partialMatchScore(stored, nodeBuffer) }
        }
    }

    /** [checkedLocations], but as the sequence they were actually visited in — see that field's own doc for why `LinkedHashSet` makes this safe to read straight off it. For a caller that wants to number/replay the path an episode took (e.g. step numbers on a map), not membership testing (which is what [checkedLocations] itself is for). */
    fun checkedLocationsInOrder(): List<Location> = checkedLocations.toList()

    /**
     * A [CmpGoal] proposing where to look next to tell the currently tied
     * hypotheses apart — see [suggestGoalLocation]'s own doc for the
     * underlying geometry, and real Monty's `propose_goals()` pipeline this
     * mirrors: a separate output channel from [getOutput], not folded into
     * its `CmpMessage` (real Monty keeps movement guidance out of
     * `get_output()`/`send_out_vote()` too), and sent as [SenderType.GSG]
     * rather than this LM's own [SenderType.LM] — the same
     * `sender_type="GSG"` real Monty's `EvidenceGoalGenerator` uses when it
     * builds a `Goal` on this LM's behalf. Null whenever there's nothing to
     * disambiguate — not [RecognitionResult.Ambiguous] yet, or nothing left
     * unchecked to suggest — in which case the caller ([MotorSystem]) should
     * fall back to its own default exploration policy (e.g. a random
     * unchecked location), the same way real Monty falls back to a naive
     * policy when goal-driven actions are off.
     *
     * This LM's own configured [positionTolerance] is forwarded to
     * [suggestGoalLocation]'s [checkedLocations] check — see the class doc
     * for why that value lives on the constructor rather than as a
     * parameter here.
     */
    fun proposeGoal(): CmpGoal? {
        val result = recognitionResult()
        if (result !is RecognitionResult.Ambiguous) return null
        val location =
            suggestGoalLocation(memory, result.labels, nodeBuffer, checkedLocations, positionTolerance) ?: return null
        logger.debug(TAG) { "[$lmId] proposing goal $location to disambiguate ${result.labels}" }
        return CmpGoal(
            location = location,
            feature = null,
            confidence = 1f,
            passMessage = true,
            senderId = lmId,
            senderType = SenderType.GSG,
            processFeaturesInLm = true,
            goalTolerances = null,
            info = null,
        )
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

    /**
     * Combines [possibleMatches] and [evidenceSnapshot] into the three-way UI
     * decision — see the top-level [recognitionResult]. Downgrades a would-be
     * [RecognitionResult.Recognized] to [RecognitionResult.Unknown] ("nothing
     * settled yet, keep exploring" — indistinguishable from genuine
     * [RecognitionResult.Unknown] to [Explorer.explore]'s own loop, which
     * treats anything but [RecognitionResult.Recognized] as "keep going")
     * while fewer than [MIN_OBSERVATIONS] nodes have been observed this
     * episode: mirrors real Monty's own terminal-state gating, which never
     * even checks whether an episode is done until `min_eval_steps`/
     * `min_train_steps` have elapsed (`monty_base.py`) — otherwise a *single*
     * lucky matching node, with only one object ever taught to compare
     * against, would trivially be "the unique match" on its own. Scaled way
     * down from Monty's own default (`min_eval_steps: 20`, tuned for
     * point clouds with hundreds of points) to fit graphs this app's domains
     * actually have (a character's handful of primitives, a city's handful
     * of landmarks) — see [MIN_OBSERVATIONS]'s own doc.
     */
    fun recognitionResult(): RecognitionResult {
        val result = recognitionResult(evidenceSnapshot())
        if (result is RecognitionResult.Recognized && nodeBuffer.size < minObservationsToRecognize(result.label)) {
            return RecognitionResult.Unknown
        }
        return result
    }

    /**
     * [MIN_OBSERVATIONS], capped at [label]'s own smallest taught variant's
     * node count — a 1-node object (a one-landmark city, say) is fully
     * described by a single observation, so requiring a second, unrelated
     * one before it can ever be "recognized" would make it unrecognizable
     * outright rather than just cautious.
     */
    private fun minObservationsToRecognize(label: String): Int {
        val smallestVariantSize = memory.candidatesForLabel(label).minOfOrNull { it.nodes.size } ?: return MIN_OBSERVATIONS
        return minOf(MIN_OBSERVATIONS, smallestVariantSize)
    }

    private fun toGraphNode(message: CmpMessage): GraphNode {
        val location = requireNotNull(message.location) { "Messages fed into matchingStep must carry a location" }
        val feature = requireNotNull(message.feature) { "Messages fed into matchingStep must carry a feature" }
        return GraphNode(id = nodeBuffer.size, location = location, feature = feature)
    }

    private companion object {
        const val TAG = "EvidenceGraphLM"

        /** See [recognitionResult]'s own doc for what this gates and why. */
        const val MIN_OBSERVATIONS = 2
    }
}
