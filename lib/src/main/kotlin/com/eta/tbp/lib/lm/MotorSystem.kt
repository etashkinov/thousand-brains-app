package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpGoal
import com.eta.tbp.lib.memory.Location

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
 */
class MotorSystem(
    private val randomLocation: () -> Location,
) {
    /**
     * The next [Location] to visit: the first usable goal among [goals] —
     * [CmpGoal.passMessage] set, and not already in [visited] — or, absent
     * one, [randomLocation], retried for as long as it keeps landing on an
     * already-[visited] location.
     */
    fun nextLocation(
        goals: List<CmpGoal>,
        visited: Set<Location>,
    ): Location {
        val goalLocation = goals.firstOrNull { it.passMessage }?.location?.takeIf { it !in visited }
        return goalLocation ?: generateSequence(randomLocation).first { it !in visited }
    }
}
