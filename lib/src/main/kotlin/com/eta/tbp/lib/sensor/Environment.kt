package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.memory.PositionTolerance

/**
 * Stands in for real Monty's `EmbodiedEnvironment`/dataset — the thing that
 * actually knows the scene's geometry and content, as opposed to
 * [SensorModule], which only ever reacts to wherever it's been pointed (see
 * [EnvironmentSensorModule]'s own doc for that split). Deliberately a much
 * simpler *shape* than Monty's own environment, though: real Monty steps its
 * environment with an [com.eta.tbp.lib.experiment.Experiment]-chosen
 * action and gets back whatever observation results
 * (`EmbodiedEnvironment.step(action) -> observation`), whereas this
 * interface is a direct pull — [featureAt] answers "what's here" for any
 * [Location] with no notion of physically getting there, and [randomLocation]
 * answers "give me somewhere valid" instead of a motor system computing a
 * reachable next pose itself. That's an accepted simplification for this
 * app's discrete domains (no continuous agent motion to simulate), not a
 * literal port — see [GridEnvironment] for the one concrete implementation.
 */
interface Environment {
    val size: Int

    /**
     * How close two locations must be to count as the same addressable
     * place — used both by [featureAt]'s nearest-cell lookup and, threaded
     * through [Explorer][com.eta.tbp.lib.lm.Explorer]/
     * [com.eta.tbp.lib.lm.MotorSystem]/[com.eta.tbp.lib.lm.EvidenceGraphLM],
     * to stop an automated explorer from treating a jittered re-observation
     * of somewhere already visited as new. Domain-owned rather than a
     * shared constant (e.g. [com.eta.tbp.lib.memory.GraphMatcher]'s own,
     * deliberately coarser position-error tolerance): it must stay well
     * under this environment's own minimum distinct-location spacing, or
     * genuinely different locations start collapsing into each other. See
     * [PositionTolerance]'s own doc for why this lives here rather than on
     * [Location] itself.
     */
    val positionTolerance: PositionTolerance

    /** A location [featureAt] can answer for — the one thing [Explorer][com.eta.tbp.lib.lm.Explorer]'s motor system needs when it has no goal-directed suggestion to act on instead. */
    fun randomLocation(): Location

    /**
     * Whatever's at [location] (the nearest known cell within
     * [positionTolerance], not necessarily an exact-coordinate match), or
     * `null` if nothing qualifies — never throws for an out-of-range or
     * unvisited [location], the same way a real sensor reports "nothing
     * here" rather than failing.
     */
    fun featureAt(location: Location): Feature?
}
