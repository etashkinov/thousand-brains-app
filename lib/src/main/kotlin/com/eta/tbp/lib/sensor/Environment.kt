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
 * [Location] with no notion of physically getting there. [randomLocation]
 * still answers "give me somewhere valid" rather than a motor system
 * computing a reachable pose itself, but only for placing the explorer at
 * an episode's start; every step after that goes through [adjacentLocations]
 * instead, so the *route* (not just the destination) is at least
 * block-by-block real. That's an accepted simplification for this app's
 * discrete domains (no continuous agent motion to simulate), not a literal
 * port — see [GridEnvironment] for the one concrete implementation.
 */
interface Environment {
    val size: Int

    /** Where to place the explorer at the very start of an episode — before they've set foot anywhere, so there's no "current location" yet for [adjacentLocations] to work from. [Explorer][com.eta.tbp.lib.lm.Explorer]'s motor system uses this exactly once per episode. */
    fun randomLocation(): Location

    /**
     * Every location reachable in one step from [location] — real city
     * blocks an explorer standing at [location] could walk to directly, not
     * teleport past. [Explorer][com.eta.tbp.lib.lm.Explorer]'s motor system
     * uses this for every step after the first (see [randomLocation]'s own
     * doc for that one exception): forbidding non-adjacent jumps is a
     * deliberate divergence from Monty's own "teleport anywhere valid"
     * simplification this interface used to make uniformly (see this
     * interface's own class doc) — real Monty's motor policies don't
     * teleport either, and a city explorer walking block to block is a
     * closer match to that than a free jump ever was.
     */
    fun adjacentLocations(location: Location): List<Location>

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
