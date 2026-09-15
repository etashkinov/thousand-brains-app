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
 * node feature payload [F] the same way real Monty's own `EvidenceGraphLM`
 * is reused at every hierarchy level via constructor config rather than
 * type branching — today's app has exactly one instantiation
 * (`F = `[com.eta.tbp.lib.sensor.PrimitiveFeature], wired up in
 * `RecognizerViewModel`). [com.eta.tbp.lib.lm.PrimitiveGraphLM] (Tier 1)
 * deliberately stays a separate, non-generic class — see
 * IMPLEMENTATION_PLAN.md's "why aren't PrimitiveGraphLM and (this class)
 * one shared EvidenceGraphLM" entry.
 *
 * "Episode" is one full character, spanning however many strokes [visits]
 * accumulates between [preEpisode] and [postEpisode] — the same boundary
 * [com.eta.tbp.lib.sensor.PrimitiveSensorModule] uses, matching real
 * Monty's own episode boundary (IMPLEMENTATION_PLAN.md §3.6).
 * Cross-stroke compositional generalization is still an explicitly
 * deferred problem; multi-stroke accumulation itself is not.
 *
 * Evidence accumulates live: each new primitive extends this episode's
 * node history, and [GraphMatcher.partialMatchScore] scores that growing
 * shape against every stored model. [evidenceSnapshot] passes
 * [observedNodes] to the matcher unfiltered — an earlier version
 * pre-filtered to nodes [GraphMemory.hasCompatibleFeature] recognized
 * *somewhere*, which silently dropped genuinely discriminating evidence
 * (an observation nothing taught has ever seen should count *against*
 * every candidate, the way real Monty treats an out-of-range observation —
 * not vanish from the comparison). Don't reintroduce that filter.
 *
 * [getOutput] mirrors real Monty's `get_output()`: a single-hypothesis
 * point estimate, never a full evidence map — Monty puts multi-hypothesis
 * evidence in `send_out_vote()` instead, not the uniform feed-forward
 * message. The full evidence-by-label breakdown this app's UI needs (live
 * bars, tie detection) is exposed via [evidenceSnapshot] as a direct query
 * instead, the same way Monty's own logging/experiment harness reads an
 * LM's hypothesis state directly.
 *
 * Teaching is deliberately not part of the [LearningModule] interface:
 * Monty gets ground truth from a labeled dataset, but this app's v1 design
 * is a human teaching by drawing + labeling, so [teach] is this app's
 * equivalent of that ground truth. It's gated on [ExperimentMode] the way
 * real Monty's `GraphLM.update_ltm_from_stm()` only writes memory
 * `if self.mode is ExperimentMode.TRAIN`; [mode] defaults to TRAIN so
 * every existing caller keeps working, and only a caller that explicitly
 * requests EVALUATE (e.g. [Experiment.evaluate]) gets the stricter
 * no-write behavior.
 *
 * [proposeGoal] is this class's own Goal State Generator (mirrors real
 * Monty's `EvidenceGoalGenerator`; see [suggestGoalLocation]): a third
 * output channel alongside [getOutput]/[sendOutVote], travelling as a
 * [CmpGoal] the way real Monty's own `propose_goals()` does. A pure
 * function of this LM's own state, which is what keeps it generic over
 * any [Location]/[Feature] pair.
 *
 * [logger] defaults to [Logger.None] so adding it affected no existing
 * caller; pass [Logger.Console] (or an `app`-side implementation) to see
 * this class's events.
 *
 * [positionTolerance] — "how close is close enough to be the same place"
 * — is set once at construction the way real Monty's
 * `EvidenceGoalGenerator.__init__` takes `goal_tolerances` as constructor
 * config, not a per-call argument. [Explorer] reads this same property for
 * its own `visited`-location bookkeeping rather than holding its own copy,
 * so the two never disagree on what "same place" means. It lives on this
 * LM rather than [com.eta.tbp.lib.sensor.Environment]: how forgiving a
 * comparison should be is a property of the learning logic doing the
 * comparing, not of the world being explored.
 */
class EvidenceGraphLM(
    override val lmId: String,
    val positionTolerance: Float = 0.3f,
    private val logger: Logger = Logger.Console,
) : LearningModule<Map<String, List<GraphObjectModel>>> {
    /**
     * Owned and constructed here, not injected — mirrors real Monty's
     * `EvidenceGraphLM.__init__` building its own `self.graph_memory`. A
     * prior constructor-injected version only existed to let [Experiment]
     * share one mutable [GraphMemory] across LM instances it rebuilds every
     * episode; [state]/[loadState] (the `LearningModule` contract's own
     * checkpoint mechanism, matching Monty's `state_dict` save/restore) is
     * the correct way to move taught knowledge between instances instead.
     */
    private val memory = GraphMemory()

    /**
     * One location this episode's [matchingStep] actually checked, in
     * visit order — [feature] is what was observed there, or `null` for a
     * featureless cell. No [GraphNode]/id stored here: [observedNodes]
     * reconstructs one for every feature-bearing [Visit] on demand, cheap
     * at this app's scale.
     *
     * [decision] is what [recordObservationDecision] concluded from this
     * visit (null until that runs; permanently null for a featureless
     * visit). [goalDecision] is a goal [proposeGoal] announced right after
     * this visit, before the next one happened. Together these replace a
     * separate flat decision log: every decision is already about a
     * specific visit (its own, or the one immediately before it), so
     * nothing a parallel list would capture is missing here.
     */
    private class Visit(
        val location: Location,
        val feature: Feature?,
    ) {
        var decision: LmDecision? = null
        var goalDecision: LmDecision? = null
    }

    private val visits = mutableListOf<Visit>()
    private var lastCompletedNodes: List<GraphNode>? = null
    private var mode = ExperimentMode.TRAIN

    override fun matchingStep(messages: List<CmpMessage>) {
        for (message in messages) {
            val location = message.location ?: continue
            // A plain linear scan, not a Set — episodes stay small (see GraphMatcher's own doc
            // for the same call made about node lookups). A location already in visits means a
            // motor system backtracking through an already-walked cell (see Explorer/MotorSystem);
            // recording it again would double-count the same observation.
            if (visits.any { it.location == location }) continue
            if (!message.passMessage) {
                visits += Visit(location, feature = null)
                continue
            }
            val feature = requireNotNull(message.feature) { "Messages fed into matchingStep must carry a feature" }
            val previousPossible = currentPossibleMatches()
            val visit = Visit(location, feature)
            visits += visit
            logger.debug(TAG) { "[$lmId] observed '${feature.label}' at $location" }
            recordObservationDecision(visit, previousPossible)
        }
    }

    /**
     * Turns this newly-recorded [visit] into its own [LmDecision], by
     * comparing [currentPossibleMatches] right before and after its
     * feature joined evidence — the same evidence-threshold transition
     * real Monty's own `_threshold_possible_matches` recomputes every step
     * (`evidence_matching/learning_module.py`), just captured as data (see
     * [decisionLog]) instead of only a debug log line.
     */
    private fun recordObservationDecision(
        visit: Visit,
        previousPossible: List<String>,
    ) {
        val feature = requireNotNull(visit.feature) { "recordObservationDecision requires a featured visit" }
        val newPossible = currentPossibleMatches()
        val message = describeObservation(feature, previousPossible, newPossible)
        visit.decision = LmDecision(location = visit.location, message = message, state = hypothesisStateOf(newPossible))
        logger.debug(TAG) { "[$lmId] decision: $message" }
    }

    /**
     * Phrases what [feature] just did to the hypothesis set, from
     * [previousPossible] to [newPossible]. One observation is one
     * sentence even when it rules out several hypotheses at once: real
     * Monty's own evidence accumulation can drop several hypotheses off
     * `possible_matches` in a single step (an observation that fits none
     * of them is evidence against all of them at once, per
     * [com.eta.tbp.lib.memory.GraphMatcher]'s own class doc), so any
     * labels just ruled out are folded into this same sentence's
     * "Rules out ..." suffix rather than getting a decision of their own.
     */
    private fun describeObservation(
        feature: Feature,
        previousPossible: List<String>,
        newPossible: List<String>,
    ): String {
        val discarded = previousPossible - newPossible.toSet()
        // Order-sensitive: each branch's condition assumes every earlier, more specific one already failed.
        val summary =
            when {
                previousPossible.isEmpty() && newPossible.isEmpty() -> "'${feature.label}' doesn't match anything taught yet."
                previousPossible.isEmpty() && newPossible.size == 1 ->
                    "'${feature.label}' is a known location for '${newPossible.single()}'."
                previousPossible.isEmpty() -> "'${feature.label}' could belong to ${newPossible.joinToString()}."
                newPossible.isEmpty() -> "'${feature.label}' isn't confirmed by anything still possible."
                newPossible.size == 1 && discarded.isNotEmpty() -> "'${feature.label}' confirms '${newPossible.single()}'."
                discarded.isEmpty() -> "'${feature.label}' still consistent with ${newPossible.joinToString()}."
                else -> "'${feature.label}' narrows it down to ${newPossible.joinToString()}."
            }
        return if (discarded.isEmpty()) summary else "$summary Rules out ${discarded.joinToString()}."
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
        val nodes = observedNodes()
        if (nodes.isEmpty()) return emptyMap()
        return memory.allLabels().associateWith { label ->
            memory.candidatesForLabel(label).maxOf { stored -> GraphMatcher.partialMatchScore(stored, nodes) }
        }
    }

    /**
     * This episode's reasoning trail, in order — see [LmDecision] for why
     * this exists as structured data alongside [logger]'s free-form debug
     * output. Each [Visit] contributes its [Visit.decision] (if any)
     * followed by its [Visit.goalDecision] (if any) — since both are only
     * ever set in that relative order (see [Visit]'s own doc), this
     * reproduces true chronological order without a separate log or step
     * counter. Safe to call mid-episode, not just after [postEpisode].
     */
    fun decisionLog(): List<LmDecision> = visits.flatMap { listOfNotNull(it.decision, it.goalDecision) }

    /** Every [visits] location, in the order [matchingStep] actually checked it — for a caller that wants to number/replay the path an episode took (e.g. step numbers on a map). */
    fun checkedLocationsInOrder(): List<Location> = visits.map { it.location }

    /**
     * [checkedLocationsInOrder], each paired with the [HypothesisState]
     * this episode held right after that visit. A featureless [Visit] has
     * no [Visit.decision] of its own, so it carries forward whatever the
     * last real observation left behind — every visited location gets a
     * state to show (e.g. coloring a UI's walked path), not just the ones
     * that bore a feature. Starts at [HypothesisState.NoMatch].
     */
    fun visitedLocationsWithState(): List<Pair<Location, HypothesisState>> {
        var current: HypothesisState = HypothesisState.NoMatch
        return visits.map { visit ->
            visit.decision?.state?.let { current = it }
            visit.location to current
        }
    }

    /**
     * A [CmpGoal] proposing where to look next to tell the currently tied
     * hypotheses apart — see [suggestGoalLocation] for the underlying
     * geometry. A separate output channel from [getOutput] (real Monty
     * keeps movement guidance out of `get_output()`/`send_out_vote()` too),
     * sent as [SenderType.GSG] rather than this LM's own [SenderType.LM],
     * matching real Monty's `EvidenceGoalGenerator`. Null whenever there's
     * nothing to disambiguate, in which case the caller ([MotorSystem])
     * should fall back to its own default exploration policy.
     *
     * [MotorSystem] only ever walks one unvisited step per call, so this
     * fires again on every intermediate step toward the same still-tied
     * goal; a new [LmDecision] is only recorded when the goal has actually
     * changed since the last one this log holds (found by scanning back
     * for the most recent [Visit.goalDecision], which need not be on the
     * immediately preceding [Visit]). [visits] is guaranteed non-empty
     * here: reaching [RecognitionResult.Ambiguous] already required at
     * least one observation.
     */
    fun proposeGoal(): CmpGoal? {
        val result = recognitionResult()
        if (result !is RecognitionResult.Ambiguous) return null
        val location =
            suggestGoalLocation(
                memory = memory,
                tiedLabels = result.labels,
                observedNodes = observedNodes(),
                checkedLocations = visits.map { it.location },
                positionTolerance = positionTolerance,
            ) ?: return null
        logger.debug(TAG) { "[$lmId] proposing goal $location to disambiguate ${result.labels}" }
        val lastGoal = visits.lastOrNull { it.goalDecision != null }?.goalDecision
        if (lastGoal?.location != location) {
            visits.lastOrNull()?.goalDecision =
                LmDecision(
                    location = location,
                    message = "Still tied between ${result.labels.joinToString()} — heading there to tell them apart.",
                    state = HypothesisState.Tied(result.labels),
                )
        }
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
        visits.clear()
        logger.debug(TAG) { "[$lmId] episode started" }
    }

    override fun postEpisode() {
        val nodes = observedNodes()
        if (nodes.isNotEmpty()) {
            lastCompletedNodes = nodes
        }
        logger.debug(TAG) { "[$lmId] episode ended with ${nodes.size} node(s) observed" }
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

    /** Labels within [xPercentThreshold]% of the max evidence — delegates to the top-level [possibleMatches]. Named to avoid shadowing that top-level function. */
    private fun currentPossibleMatches(xPercentThreshold: Float = 10f): List<String> =
        possibleMatches(evidenceSnapshot(), xPercentThreshold)

    /**
     * Combines [currentPossibleMatches] and [evidenceSnapshot] into the
     * three-way UI decision — see the top-level [recognitionResult].
     * Downgrades a would-be [RecognitionResult.Recognized] to
     * [RecognitionResult.Unknown] while fewer than [MIN_OBSERVATIONS]
     * nodes have been observed this episode, mirroring real Monty's own
     * terminal-state gating (never checks whether an episode is done
     * until `min_eval_steps`/`min_train_steps` have elapsed) — otherwise a
     * single lucky matching node, with only one object ever taught to
     * compare against, would trivially be "the unique match". Scaled down
     * from Monty's own default (`min_eval_steps: 20`) to fit graphs this
     * app's domains actually have — see [MIN_OBSERVATIONS].
     */
    fun recognitionResult(): RecognitionResult {
        val result = recognitionResult(evidenceSnapshot())
        val observedNodeCount = visits.count { it.feature != null }
        if (result is RecognitionResult.Recognized && observedNodeCount < minObservationsToRecognize(result.label)) {
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

    /**
     * A [GraphNode] for every feature-bearing [visits] entry, in visit
     * order. [GraphNode.id] is that visit's own index into [visits] —
     * unique and monotonic, which is all [GraphNode.equals]/[edgeChainOf]
     * need, even though a featureless visit interleaved between two
     * observations means the ids used here skip around.
     */
    private fun observedNodes(): List<GraphNode> =
        visits.withIndex().mapNotNull { (index, visit) -> visit.feature?.let { GraphNode(index, visit.location, it) } }

    private companion object {
        const val TAG = "EvidenceGraphLM"

        /** See [recognitionResult]'s own doc for what this gates and why. */
        const val MIN_OBSERVATIONS = 2
    }
}
