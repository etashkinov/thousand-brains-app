package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpGoal
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.memory.anyNear

/**
 * Mirrors real Monty's `MotorSystem`/`RuntimeMotorSystem`
 * (`motor_system.py`): the distinct object [Explorer] hands its aggregated
 * [CmpGoal] messages to, each step, to decide the actual next move — not
 * logic left inlined in the LM-stepping loop. Real Monty's
 * `MotorSystem.__call__(ctx, observations,
 * proprioceptive_state, percept, goals: Sequence[Goal]) -> list[Action]`
 * delegates to a `MotorPolicySelector` choosing among continuous 3D
 * `Action`s when no goal applies; this class's [nextLocation] plays that
 * same "prefer the goal, else fall back to a naive policy" role with one
 * direct decision instead, matching Monty's own `Monty` + `MotorSystem`
 * split rather than changing what the decision does.
 *
 * Unlike an earlier version of this class, [nextLocation] can't jump
 * anywhere in the environment that's still unvisited — it's constrained to
 * [current]'s own [adjacentLocations], the same way real Monty's own motor
 * policies never teleport either (`NaiveScanPolicy` applies a small fixed
 * relative step, not an arbitrary one). A city explorer walks block to
 * block; [current] not having *any* unvisited neighbor (a dead end) means
 * backtracking down an already-walked block to reach a different one, not
 * giving up — [path] is exactly that backtrack stack, making this class
 * genuinely stateful across a call sequence within one episode (unlike the
 * old teleport-anywhere version, which needed no memory of how it got
 * anywhere). Still constructed fresh per episode, same as before.
 *
 * [goals] stays a list (matching Monty's own `Sequence[Goal]`) even though
 * this app has exactly one goal producer today ([EvidenceGraphLM.proposeGoal]) —
 * free of charge, and it's what a second LM voting into the same motor
 * system (Phase 8) would need anyway.
 *
 * [positionTolerance] is constructor config, same as [adjacentLocations] —
 * not a per-[nextLocation]-call argument, so every step within an episode
 * necessarily agrees on what "already visited" means. [Explorer] constructs
 * this with its own [EvidenceGraphLM.positionTolerance] rather than a value
 * of its own — see that property's doc for why there's exactly one
 * configured value, not a copy per class.
 */
class MotorSystem(
    private val adjacentLocations: (Location) -> List<Location>,
    private val positionTolerance: Float = 0.3f,
) {
    /** Every [current] this episode has already moved on from, in visit order — [nextLocation] pops it to backtrack once [current] is a dead end (every neighbor already [visited]). */
    private val path = ArrayDeque<Location>()

    /**
     * The next [Location] to move to from [current]: among [current]'s own
     * unvisited [adjacentLocations], the one closest to the first usable
     * goal among [goals] ([CmpGoal.passMessage] set) if there is one,
     * otherwise the first neighbor [adjacentLocations] itself returns. If
     * every neighbor is already in [visited] (within [positionTolerance]),
     * backtracks to whichever [current] this episode moved on from most
     * recently instead — `null` only once that backtrack stack is itself
     * exhausted, meaning every location reachable from the episode's start
     * has been visited.
     */
    fun nextLocation(
        current: Location,
        goals: List<CmpGoal>,
        visited: Collection<Location>,
    ): Location? {
        val unvisitedNeighbors = adjacentLocations(current).filterNot { visited.anyNear(it, positionTolerance) }
        if (unvisitedNeighbors.isEmpty()) return path.removeLastOrNull()

        path.addLast(current)
        val goalLocation = goals.firstOrNull { it.passMessage }?.location
        return if (goalLocation != null) {
            unvisitedNeighbors.minBy { it.displacement(goalLocation).magnitude() }
        } else {
            unvisitedNeighbors.first()
        }
    }
}
