package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpGoal
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.memory.PositionTolerance

/**
 * Mirrors real Monty's `MotorSystem`/`RuntimeMotorSystem`
 * (`motor_system.py`): the distinct object [Explorer] hands its aggregated
 * [CmpGoal] messages to, each step, to decide the actual next move — not
 * logic left inlined in the LM-stepping loop. Real Monty's
 * `MotorSystem.__call__(ctx, observations,
 * proprioceptive_state, percept, goals: Sequence[Goal]) -> list[Action]`
 * delegates to a `MotorPolicySelector` choosing among continuous 3D
 * `Action`s when no goal applies; this app's domain is a discrete teleport
 * — visiting any [Location] directly, with no path to travel — so
 * [nextLocation] plays that same "prefer the goal, else fall back to a
 * naive policy" role with one direct decision instead. That's the same
 * stated simplification [Explorer]'s own class doc already makes for why
 * it doesn't port `MotorPolicySelector` itself; this class only extracts
 * the decision into its own named unit, matching Monty's own `Monty` +
 * `MotorSystem` split, rather than changing what the decision does.
 *
 * [goals] stays a list (matching Monty's own `Sequence[Goal]`) even though
 * this app has exactly one goal producer today ([EvidenceGraphLM.proposeGoal]) —
 * free of charge, and it's what a second LM voting into the same motor
 * system (Phase 8) would need anyway.
 *
 * Stateless and constructed fresh per episode (from that episode's own
 * [randomLocation] source) rather than held across episodes like real
 * Monty's `MotorSystem` — that class is a persistent, constructor-injected
 * object only because it *also* carries `_action_sequence`/
 * `motor_only_step` telemetry state this app's one-shot "pick a location"
 * decision has no equivalent of.
 *
 * [positionTolerance] (default [PositionTolerance.EXACT]) is constructor
 * config, same as [randomLocation] — not a per-[nextLocation]-call
 * argument, so every step within an episode necessarily agrees on what
 * "already visited" means. [Explorer] constructs this with its own
 * [EvidenceGraphLM.positionTolerance] rather than a value of its own — see
 * that property's doc, and [PositionTolerance]'s own, for why there's
 * exactly one configured value, not a copy per class.
 */
class MotorSystem(
    private val randomLocation: () -> Location,
    private val positionTolerance: PositionTolerance = PositionTolerance.EXACT,
) {
    /**
     * The next [Location] to visit: the first usable goal among [goals] —
     * [CmpGoal.passMessage] set, and not within [positionTolerance] of
     * anything in [visited] — or, absent one, [randomLocation], retried for
     * as long as it keeps landing within [positionTolerance] of an
     * already-[visited] location.
     */
    fun nextLocation(
        goals: List<CmpGoal>,
        visited: Set<Location>,
    ): Location {
        val goalLocation = goals.firstOrNull { it.passMessage }?.location?.takeIf { !positionTolerance.anyNear(visited, it) }
        return goalLocation ?: generateSequence(randomLocation).first { !positionTolerance.anyNear(visited, it) }
    }
}
