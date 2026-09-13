package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpGoal
import com.eta.tbp.lib.log.Logger
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.Location
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
 * called for, in whatever order, ending at [endExploration] — the same
 * episode-boundary idiom [lm] itself already uses for a character. Locations
 * don't need to be adjacent or visited in any particular order:
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
 * `NaiveScanPolicy` never receives (and the [SensorModule] interface never
 * exposes) "everywhere the sensor could go" — it applies a small fixed
 * relative step, over and over, capped by a step count
 * (`check_reached_max_matching_steps`), and defers to a goal state once the
 * LM has one to offer. [MotorSystem] mirrors that same "prefer the goal,
 * else fall back" shape rather than real Monty's actual
 * `MotorSystem`/`MotorPolicySelector` machinery (`motor_system.py`, built
 * for continuous 3D agent motion — far more than this domain needs): prefer
 * [proposeGoal]'s goal-directed pick; otherwise ask [randomLocation] for a
 * candidate, retrying if it's one [visit] already used this episode; stop
 * after [maxSteps] regardless. That's a deliberate, stated exception, the
 * same category IMPLEMENTATION_PLAN.md's own compatibility table already
 * makes for the touch tier ("Motor system + simulator driving a sensor" →
 * "Not ported — the human *is* the motor system"); here nothing plays that
 * role for an *automated* explorer, so [explore] has to.
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

    /** Moves to [location] (not necessarily adjacent to the last one) and folds its observed feature into the current exploration. */
    private fun visit(location: Location) {
        logger.debug(TAG) { "visiting $location" }
        lm.matchingStep(listOf(sensorModule.step(location)))
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

    /**
     * A live look at the recognition state so far this exploration —
     * unlike [endExploration], this doesn't end the episode, so a caller
     * (or [explore]) can check it after every single [visit] and keep
     * exploring on anything other than a unique [RecognitionResult.Recognized].
     */
    fun currentResult(): RecognitionResult = lm.recognitionResult()

    /** The locations observed so far this exploration, in visit order — direct introspection, same spirit as [EvidenceGraphLM.currentNodes]. */
    fun currentNodes(): List<GraphNode> = lm.currentNodes()

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
     * a fresh [MotorSystem] built from [randomLocation] and [lm]'s own
     * configured [EvidenceGraphLM.positionTolerance] (see that property's
     * doc for why this class reads it from [lm] rather than taking its own
     * copy as a parameter here). At each step: [MotorSystem.nextLocation]
     * prefers [proposeGoal]'s goal-directed pick; if it's null or already
     * visited this episode, it asks [randomLocation] instead, retrying it
     * for as long as it keeps returning an already-visited location. Stops
     * early on [RecognitionResult.Recognized] unless [everything] is set —
     * teaching a second object that shares landmarks with an already-taught
     * one needs every candidate observed, not just however many it took to
     * (mis)match the first thing already known (see [EvidenceGraphLM.teach]'s
     * own doc for why teaching needs the complete sequence).
     *
     * [randomLocation] must not be able to produce more than [maxSteps]
     * distinct locations — once every location it can produce has been
     * visited, [MotorSystem]'s already-visited retry never terminates. A
     * caller whose domain is a bounded space (e.g. a city's NxN grid) should
     * size [maxSteps] to that space's own extent, the same way real Monty's
     * `NaiveScanPolicy` is bounded by a caller-configured step count, not by
     * anything it discovers about the environment itself. That bound holds
     * regardless of [lm]'s [EvidenceGraphLM.positionTolerance]: a domain like
     * [GridEnvironment][com.eta.tbp.lib.sensor.GridEnvironment] still only
     * ever samples the same finite set of distinct locations — a nonzero
     * tolerance can only shrink how many of them count as distinct (each
     * visited location also excludes its close neighbors), never grow the
     * set [randomLocation] draws from.
     */
    fun explore(
        randomLocation: () -> Location,
        maxSteps: Int,
        everything: Boolean = false,
    ): ExplorationOutcome {
        beginExploration()
        val motorSystem = MotorSystem(randomLocation, lm.positionTolerance)
        var locationsVisited = 0
        while (locationsVisited < maxSteps) {
            val visited = currentNodes().map { it.location }.toSet()
            val goals = listOfNotNull(proposeGoal())
            val next = motorSystem.nextLocation(goals, visited)

            logger.debug(TAG) { "step ${locationsVisited + 1}: visiting $next (${if (goals.any { it.location == next }) "goal-suggested" else "random"})" }
            visit(next)
            locationsVisited++

            if (!everything && currentResult() is RecognitionResult.Recognized) break
            // Ambiguous or Unknown: not resolved yet, keep moving.
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
