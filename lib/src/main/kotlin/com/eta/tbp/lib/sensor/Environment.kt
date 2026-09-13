package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.Location

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

    /** A location [featureAt] can answer for — the one thing [Explorer][com.eta.tbp.lib.lm.Explorer]'s motor system needs when it has no goal-directed suggestion to act on instead. */
    fun randomLocation(): Location

    /**
     * Whatever's at [location] — the nearest known cell within [tolerance],
     * not necessarily an exact-coordinate match — or `null` if nothing
     * qualifies. Never throws for an out-of-range or unvisited [location],
     * the same way a real sensor reports "nothing here" rather than
     * failing.
     *
     * [tolerance] is supplied by the caller
     * ([EnvironmentSensorModule], configured with whatever value the
     * learning logic considers "the same place" — see
     * [com.eta.tbp.lib.lm.EvidenceGraphLM.positionTolerance]'s own doc)
     * rather than owned by this [Environment]: how forgiving a match counts
     * as "close enough" is a property of the process doing the comparing,
     * not a fact about the world being queried — real Monty's own
     * continuous environment has no such notion at all (there's no
     * discreteness for it to be imprecise about); every bit of positional
     * forgiveness on Monty's side lives on the LM comparing a *current*
     * observation against its own *learned* graph
     * (`GraphMatcher`-equivalent), never on the environment/dataset. This
     * interface still owns the *mechanism* (nearest-cell search) because
     * it's the only thing that knows what its own known locations are —
     * just not the *value*.
     */
    fun featureAt(
        location: Location,
        tolerance: Float,
    ): Feature?
}
