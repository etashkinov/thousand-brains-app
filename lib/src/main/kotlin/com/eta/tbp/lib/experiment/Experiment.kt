package com.eta.tbp.lib.experiment

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.ExperimentMode
import com.eta.tbp.lib.lm.ExplorationOutcome
import com.eta.tbp.lib.lm.Explorer
import com.eta.tbp.lib.lm.HypothesisState
import com.eta.tbp.lib.lm.LmDecision
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.log.Logger
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.Environment
import com.eta.tbp.lib.sensor.EnvironmentSensorModule

/**
 * Mirrors real Monty's `MontyExperiment` — the harness that drives a
 * `Monty`/`MontyBase` object through episodes, kept in its own package
 * (`frameworks/experiments/`) separate from the "brain" itself
 * (`frameworks/models/`, where `Monty`/`MontyBase`/`LearningModule`/
 * `SensorModule` live). [Explorer] is this app's `models`-layer
 * counterpart — this class is the `experiments`-layer one, wiring
 * [sensor]/[lm]/[explorer] together and running [train]/[evaluate], the
 * same layering split, not one flat package for both.
 *
 * Automates the "explore until recognized" loop end to end, per TBP's own
 * framing: an explorer who finds themselves in [environment] doesn't know
 * their own coordinate on arrival, so each episode defaults to starting at a
 * random location (not necessarily a discriminating one — see
 * [GraphMatcher][com.eta.tbp.lib.memory.GraphMatcher]'s doc for why anchoring
 * can't assume the first thing observed is), then walks it block by block
 * from there — [environment]'s own [Environment.adjacentLocations], not a
 * teleport to anywhere still unvisited — checking the recognition state
 * after every visit:
 *
 * - [RecognitionResult.Recognized] (unique match) → stop, report it.
 * - [RecognitionResult.Ambiguous] (multiple known objects still fit) → ask
 *   the LM (via [Explorer.proposeGoal], its own embedded Goal State
 *   Generator — see [EvidenceGraphLM.proposeGoal]) where visiting
 *   next would best tell the tied candidates apart, rather than picking
 *   blindly. Real Monty does the same once its own hypotheses narrow.
 * - [RecognitionResult.Unknown], or no suggestion available → nothing to
 *   disambiguate between yet, so just visit the next random location.
 *
 * This whole loop — [Explorer]'s motor system, see its own class doc — is
 * generic and lives in [Explorer.explore]; [runEpisode] here only supplies
 * [environment]'s own starting-location sampler, its adjacency function, and
 * a step cap sized well past [environment]'s own cell count (see
 * [runEpisode]'s own doc for why). [Explorer] never sees the environment
 * itself, only a way to ask for a starting location and, from any location,
 * its neighbors — the same separation real Monty keeps between an SM (never
 * told the full observation space) and the environment/dataset that
 * actually knows it.
 *
 * [train] and [evaluate] mirror `MontyExperiment.train()`/`.evaluate()`, not
 * one method with a mode flag: they diverge in exactly one place, whether
 * the whole-environment-toured-with-nothing-recognized case teaches a new
 * object or just reports the miss. That divergence exists in real Monty
 * too, and exactly this way — see [EvidenceGraphLM.teach]'s own doc for the
 * `update_ltm_from_stm` gate this mirrors. [evaluate] never writes to
 * [lm]'s memory, regardless of outcome; [train] always resolves to either
 * an existing object or a newly taught one, never a bare miss.
 *
 * Both default their `start` to [environment]'s own [Environment.randomLocation],
 * per the framing above, but a caller that already knows where to place the
 * explorer (e.g. a UI letting a person pick a starting cell before running
 * either mode) can pass one explicitly instead.
 *
 * Every location [environment] can produce is a *candidate* to visit, not
 * just the ones bearing a feature: a real explorer doesn't know in advance
 * which locations are worth recording — that's discovered by visiting,
 * exactly like [EnvironmentSensorModule] mirrors a real Monty `SensorModule`'s
 * per-observation reporting. Touring stays cheap regardless of how much of
 * [environment] ends up visited, since [EnvironmentSensorModule] reports a
 * featureless location as `passMessage = false` — one lookup, never
 * touching the LM's evidence.
 *
 * [logger] defaults to [Logger.Console] and, when set, is handed down to
 * every [EnvironmentSensorModule]/[EvidenceGraphLM]/[Explorer] this class
 * wires up — one [Logger] for the whole pipeline, each layer tagging its
 * own events.
 *
 * [positionTolerance] is likewise
 * handed to both [lm] and [sensor] — [environment] itself owns none of
 * this (see [Environment.featureAt]'s own doc for why "how close counts as
 * the same place" belongs to the learning logic, not the world being
 * queried): this class is simply the one wiring point that hands the same
 * configured value to whichever components need it, [lm] built first so
 * [sensor] can be given [EvidenceGraphLM.positionTolerance] rather than a
 * second, independently-supplied copy of the same value.
 *
 * [sensor]/[lm]/[explorer] are built exactly once, in this class's own
 * initializer, and reused by every [train]/[evaluate] call on this
 * instance — mirroring real Monty's own `Monty` object, which is
 * constructed once per experiment and persists across every episode
 * (`MontyExperiment.pre_episode()` calls `self.model.reset()`, it never
 * recreates `self.model`). [Explorer.explore] relies on
 * [Explorer.beginExploration]/[Explorer.endExploration] (which forward to
 * [lm]/[sensor]'s own `preEpisode()`/`postEpisode()`) to clear
 * per-episode-only state between calls — [lm]'s taught objects are never
 * touched by that reset, exactly like [lm]'s own doc describes for
 * [EvidenceGraphLM.preEpisode].
 *
 * [lm] owns and constructs its own [com.eta.tbp.lib.memory.GraphMemory]
 * internally (see [EvidenceGraphLM]'s own doc) rather than this class
 * building one and injecting it — moving previously taught objects into a
 * fresh [Experiment] (e.g. a new "session" recognizing what an earlier one
 * taught) goes through [state]/[loadState] instead, the same
 * checkpoint-restore idiom real Monty uses to load a pretrained model.
 *
 * Generic over [environment]'s domain — a city's grid ([com.eta.tbp.lib.sensor.GridEnvironment])
 * is the one concrete [Environment] this app has today, but nothing here
 * knows about cells, cities, or [com.eta.tbp.lib.memory.LabelFeature]; a
 * different [Environment] plugs in the same way [Explorer] takes any
 * [com.eta.tbp.lib.sensor.SensorModule].
 */
class Experiment(
    private val environment: Environment,
    private val lmId: String = "lm-0",
    positionTolerance: Float = 0.3f,
    private val logger: Logger = Logger.Console,
) {
    init {
        logger.info(TAG) { "Experiment(lmId='$lmId', positionTolerance=$positionTolerance) on $environment" }
    }

    private val lm = EvidenceGraphLM(lmId = lmId, positionTolerance = positionTolerance, logger = logger)
    private val sensor =
        EnvironmentSensorModule(
            sensorId = "$lmId-sensor",
            positionTolerance = lm.positionTolerance,
            logger = logger,
        )
    private val explorer = Explorer(sensor, lm, logger = logger)

    sealed class Outcome {
        /** [locationsVisited] is how many locations it took before the match became unique — never more than [environment]'s own reachable location count. */
        data class Recognized(
            val label: String,
            val confidence: Float,
            val locationsVisited: Int,
        ) : Outcome()

        /** [evaluate] only: [environment] was toured in full and nothing matched — nothing was taught. */
        data class NoMatch(
            val locationsVisited: Int,
        ) : Outcome()

        /** [train] only: [environment] was toured in full and nothing matched, so [label] was just taught as a new object. */
        data class Taught(
            val label: String,
            val locationsVisited: Int,
        ) : Outcome()
    }

    /** Explores [environment] and, if nothing already taught uniquely matches, teaches [label] as a new object — real Monty's TRAIN behavior (a known ground-truth label, supplied by the caller the same way a labeled dataset entry supplies one). */
    fun train(
        label: String,
        start: Location = environment.randomLocation(),
    ): Outcome {
        logger.info(TAG) { "train('$label') starting on $environment" }
        val exploration = runEpisode(ExperimentMode.TRAIN, start)
        val result = exploration.result
        val outcome =
            if (result is RecognitionResult.Recognized) {
                Outcome.Recognized(result.label, result.confidence, exploration.locationsVisited)
            } else {
                explorer.teach(label) // Belt-and-suspenders: the real gate is EvidenceGraphLM's own ExperimentMode check.
                Outcome.Taught(label, exploration.locationsVisited)
            }
        logger.info(TAG) { "train('$label') finished: $outcome" }
        return outcome
    }

    /** Explores [environment] purely to check it against what's already known — never writes to [lm]'s memory, matching real Monty's EVALUATE behavior. */
    fun evaluate(start: Location = environment.randomLocation()): Outcome {
        logger.info(TAG) { "evaluate() starting on $environment" }
        val exploration = runEpisode(ExperimentMode.EVALUATE, start)
        val result = exploration.result
        val outcome =
            if (result is RecognitionResult.Recognized) {
                Outcome.Recognized(result.label, result.confidence, exploration.locationsVisited)
            } else {
                Outcome.NoMatch(exploration.locationsVisited)
            }
        logger.info(TAG) { "evaluate() finished: $outcome" }
        return outcome
    }

    /** [lm]'s own taught objects — for moving them into another [Experiment] instance (a fresh "session" recognizing what an earlier one taught), the same checkpoint-restore idiom real Monty uses to load a pretrained model. */
    fun state(): Map<String, List<GraphObjectModel>> = lm.state()

    /** Loads previously taught objects (from [state]) into [lm]. */
    fun loadState(state: Map<String, List<GraphObjectModel>>) = lm.loadState(state)

    /** [visitedLocations], in the order that episode actually walked them — a direct passthrough to [Explorer.checkedLocationsInOrder], for numbering/replaying the path (e.g. step numbers on a map) rather than just testing membership. */
    fun visitedLocationsInOrder(): List<Location> = explorer.checkedLocationsInOrder()

    /** The just-run episode's step-by-step reasoning — a direct passthrough to [Explorer.decisionLog], for a UI to show alongside [visitedLocationsInOrder] why the outcome came out the way it did. */
    fun decisionLog(): List<LmDecision> = explorer.decisionLog()

    /** [visitedLocationsInOrder], each paired with the [HypothesisState] the LM held right after that visit — a direct passthrough to [Explorer.visitedLocationsWithState], for a UI coloring the whole walked path by belief-at-the-time rather than just marking which cells were visited. */
    fun visitedLocationsWithState(): List<Pair<Location, HypothesisState>> = explorer.visitedLocationsWithState()

    /**
     * Runs [explorer]'s motor system ([Explorer.explore]), bounded to well
     * past [environment]'s own cell count — only [mode] differs between
     * [train]/[evaluate]. The configured `positionTolerance` was already
     * handed to [lm] (and, from there, [sensor]) at construction — see this
     * class's own field initializers — so [explorer] reads it from [lm]
     * with nothing to pass here.
     *
     * [STEP_BUDGET_MULTIPLIER] scales [environment]'s cell count
     * ([Environment.size] squared) rather than reusing it directly: with
     * [Explorer.explore] now walking [environment] block by block instead of
     * teleporting, a dead end can send the explorer back over already-walked
     * ground before it reaches unvisited territory (see [Explorer.explore]'s
     * own doc), so guaranteeing every reachable cell gets seen — the same
     * guarantee a step budget of exactly `size * size` gave for free under
     * teleportation — needs headroom for that backtracking, not just one
     * step per cell.
     */
    private fun runEpisode(
        mode: ExperimentMode,
        start: Location,
    ): ExplorationOutcome {
        lm.setExperimentMode(mode)
        return explorer.explore(environment, start)
    }

    private companion object {
        const val TAG = "Experiment"

        /** See [runEpisode]'s own doc. */
        const val STEP_BUDGET_MULTIPLIER = 4
    }
}
