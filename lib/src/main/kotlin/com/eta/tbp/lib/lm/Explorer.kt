package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpGoal
import com.eta.tbp.lib.log.Logger
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.Environment
import com.eta.tbp.lib.sensor.SensorModule

/**
 * Mirrors real Monty's `Monty`/`MontyBase` object: owns exactly one
 * [SensorModule]/[EvidenceGraphLM] pair (this app's v1 scope — one SM, one
 * LM) and runs its per-step "step SM, feed into LM" loop
 * (`MontyBase.aggregate_sensory_inputs`), plus the [preEpisode]/
 * [postEpisode] boundary Monty's own `MontyBase.reset()` provides.
 *
 * No domain-specific code lives here — [SensorModule] and [EvidenceGraphLM]
 * both already operate on the generic [Location]/[com.eta.tbp.lib.memory.Feature]
 * interfaces rather than a fixed shape, so a domain (e.g. `CitySensorModule`
 * for city cells, `PrimitiveSensorModule` for stroke points) just plugs its
 * own [SensorModule] in; nothing about this class needs a per-domain
 * subclass, the same way [GraphMatcher][com.eta.tbp.lib.memory.GraphMatcher]/
 * [GraphMemory][com.eta.tbp.lib.memory.GraphMemory]/[EvidenceGraphLM] never
 * needed one either.
 *
 * "Episode" here is one exploration — however many locations [visit] is
 * called for, ending at [endExploration] — the same episode-boundary idiom
 * [lm] itself already uses for a character. [visit] itself takes any
 * [Location], adjacent to the last one or not — a caller driving it directly
 * (a test exercising this class without a full grid, say) isn't required to
 * respect adjacency, only [explore]'s own [MotorSystem] is (see its doc).
 * Nor do visits need to happen in any particular order:
 * [GraphMatcher][com.eta.tbp.lib.memory.GraphMatcher]'s translation- and
 * order-tolerant matching is exactly what makes that safe — the explorer
 * never needs to know its own coordinate in the taught model's frame, only
 * its moves relative to wherever it started.
 *
 * A [RecognitionResult.Unknown] after [endExploration] means nothing taught
 * explains what was observed — the caller can [teach] a new label to define
 * one. [RecognitionResult.Ambiguous] means multiple taught models still fit
 * everything observed so far — the caller should keep exploring (more
 * [visit] calls, then a fresh [endExploration]) rather than guess.
 *
 * [explore] additionally owns the *motor system* role real Monty's `Monty`
 * constructor takes a `motor_system` for: deciding, at each step, where to
 * go next, via a [MotorSystem] it builds for the episode. Real Monty's own
 * `NaiveScanPolicy` applies a small fixed relative step, over and over,
 * capped by a step count (`check_reached_max_matching_steps`), and defers to
 * a goal state once the LM has one to offer — never a free jump to anywhere
 * unvisited. [MotorSystem] mirrors that same "small step, prefer the goal,
 * else fall back" shape (not real Monty's actual `MotorSystem`/
 * `MotorPolicySelector` machinery, built for continuous 3D agent motion —
 * far more than this domain needs), constrained to [Environment.adjacentLocations][com.eta.tbp.lib.sensor.Environment.adjacentLocations]
 * the same way. That's a deliberate, stated exception, the same category
 * IMPLEMENTATION_PLAN.md's own compatibility table already makes for the
 * touch tier ("Motor system + simulator driving a sensor" → "Not ported —
 * the human *is* the motor system"); here nothing plays that role for an
 * *automated* explorer, so [explore] has to.
 *
 * [logger] logs only what's unique to this layer — the move itself and the
 * episode's start/end — not [sensorModule]'s or [lm]'s own events, which
 * each already log themselves if given the same [Logger]. Defaults to
 * [Logger.Console].
 */
class Explorer(
    private val sensorModule: SensorModule,
    private val lm: EvidenceGraphLM,
    private val logger: Logger = Logger.Console,
) {
    fun beginExploration() {
        lm.preEpisode()
        sensorModule.preEpisode()
        logger.debug(TAG) { "exploration started" }
    }

    /** Moves to [location] (not necessarily adjacent to the last one — see class doc) and folds its observed feature into the current exploration. Public for a caller that wants to drive an episode directly (e.g. a test exercising this class's/[EvidenceGraphLM]'s wiring without a full grid walk) rather than through [explore]'s own adjacency-constrained [MotorSystem]. */
    fun visit(
        environment: Environment,
        location: Location,
    ) {
        logger.debug(TAG) { "visiting $location" }
        lm.matchingStep(listOf(sensorModule.step(environment, location)))
    }

    fun endExploration(): RecognitionResult {
        sensorModule.postEpisode()
        lm.postEpisode()
        val result = lm.recognitionResult()
        logger.info(TAG) { "exploration ended: $result" }
        return result
    }

    /** Labels the just-ended exploration as [label] — defines a new object, or merges into an existing one taught under the same label. */
    fun teach(label: String) = lm.teach(label)

    /** [lm]'s own taught models, for moving them into another [EvidenceGraphLM] instance — see [EvidenceGraphLM]'s own doc for why [lm] owns its [com.eta.tbp.lib.memory.GraphMemory] rather than taking one externally. */
    fun state(): Map<String, List<GraphObjectModel>> = lm.state()

    /** Loads previously taught models (from [state]) into [lm] — e.g. seeding a fresh [Explorer] with what another one already taught. */
    fun loadState(state: Map<String, List<GraphObjectModel>>) = lm.loadState(state)

    /** The full evidence breakdown across every taught label — for direct introspection, same spirit as [EvidenceGraphLM.evidenceSnapshot]. */
    fun evidenceSnapshot(): Map<String, Float> = lm.evidenceSnapshot()

    /** [checkedLocations], in the order [visit] actually walked them — a direct passthrough to [EvidenceGraphLM.checkedLocationsInOrder]. */
    fun checkedLocationsInOrder(): List<Location> = lm.checkedLocationsInOrder()

    /** This episode's step-by-step reasoning so far — a direct passthrough to [EvidenceGraphLM.decisionLog], for a caller (e.g. [com.eta.tbp.lib.experiment.Experiment]) that wants to show why [explore] ended up where it did, not just the path it walked. */
    fun decisionLog(): List<LmDecision> = lm.decisionLog()

    /**
     * A live look at the recognition state so far this exploration —
     * unlike [endExploration], this doesn't end the episode, so a caller
     * (or [explore]) can check it after every single [visit] and keep
     * exploring on anything other than a unique [RecognitionResult.Recognized].
     */
    fun currentResult(): RecognitionResult = lm.recognitionResult()

    /**
     * The [CmpGoal] [lm] proposes to tell its currently tied hypotheses
     * apart — see [EvidenceGraphLM.proposeGoal]. Null under the same
     * conditions [EvidenceGraphLM.proposeGoal] is — [explore]'s own
     * [MotorSystem] falls back to its untried-candidate pick in that case.
     */
    fun proposeGoal(): CmpGoal? = lm.proposeGoal()

    /**
     * Runs one full episode, for at most [maxSteps] visits — the motor
     * system this class owns (see class doc), delegated for the episode to
     * a fresh [MotorSystem] built from [adjacentLocations] and [lm]'s own
     * configured [EvidenceGraphLM.positionTolerance] (see that property's
     * doc for why this class reads it from [lm] rather than taking its own
     * copy as a parameter here). [startLocation] places the explorer once, at
     * the very beginning, before there's any "current location" for
     * [adjacentLocations] to work from (see [com.eta.tbp.lib.sensor.Environment.randomLocation]'s
     * own doc); every step after that moves to one of the current location's
     * own [adjacentLocations] instead — [MotorSystem.nextLocation] prefers
     * whichever unvisited neighbor is closest to [proposeGoal]'s
     * goal-directed pick, else the first unvisited neighbor, else backtracks
     * down an already-walked block toward a different one. Stops early on
     * [RecognitionResult.Recognized] unless [everything] is set — teaching a
     * second object that shares landmarks with an already-taught one needs
     * every candidate observed, not just however many it took to (mis)match
     * the first thing already known (see [EvidenceGraphLM.teach]'s own doc
     * for why teaching needs the complete sequence) — or once
     * [MotorSystem.nextLocation] itself returns `null`: every location
     * reachable from [startLocation] has already been visited, so there's
     * nowhere left to backtrack to either, regardless of [maxSteps].
     *
     * [maxSteps] needs enough headroom for backtracking, not just one visit
     * per reachable location: a caller whose domain is a bounded space (e.g.
     * a city's NxN grid) should size it well above that space's own cell
     * count, since a dead end can send the explorer back over already-walked
     * ground before it reaches new territory — seeing every location doesn't
     * cost more than roughly 3× the reachable count even in the worst case
     * (each connecting step walked at most once forward and once back), but
     * a tighter budget risks giving up with the environment only partly
     * toured. Same spirit as real Monty's own `NaiveScanPolicy`, bounded by a
     * caller-configured step count rather than anything it discovers about
     * the environment itself — just a bigger number here, to pay for the
     * routes teleportation used to skip.
     */
    fun explore(
        environment: Environment,
        startLocation: Location,
        everything: Boolean = false,
    ): ExplorationOutcome {
        beginExploration()
        val motorSystem = MotorSystem(environment::adjacentLocations, lm.positionTolerance)

        var current = startLocation
        logger.debug(TAG) { "step 1: visiting $current (start)" }
        visit(environment, current)
        var locationsVisited = 1

        // see doc for why 3× the grid's own cell count is enough headroom for backtracking
        val maxSteps = environment.size * environment.size * 3
        while (locationsVisited < maxSteps && (everything || currentResult() !is RecognitionResult.Recognized)) {
            val goals = listOfNotNull(proposeGoal())
            val next = motorSystem.nextLocation(current, goals, lm.checkedLocationsInOrder()) ?: break

            logger.debug(TAG) {
                "step ${locationsVisited + 1}: visiting $next (${if (goals.any { it.location == next }) "goal-suggested" else "adjacent"})"
            }
            current = next
            visit(environment, current)
            locationsVisited++
        }

        return ExplorationOutcome(endExploration(), locationsVisited)
    }

    private companion object {
        const val TAG = "Explorer"
    }
}

/** [Explorer.explore]'s result: what it concluded, and how many locations it took to get there. */
data class ExplorationOutcome(
    val result: RecognitionResult,
    val locationsVisited: Int,
)
