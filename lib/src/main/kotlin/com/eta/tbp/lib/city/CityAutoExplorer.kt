package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.memory.GraphMemory
import kotlin.random.Random

/**
 * Automates the "detect city" loop end to end, per TBP's own framing: an
 * explorer who finds themselves in [cityMap] doesn't know their own
 * coordinate on arrival, so [explore] starts by visiting cells in a random
 * order (not just a random first cell — see [GraphMatcher][com.eta.tbp.lib.memory.GraphMatcher]'s
 * doc for why anchoring can't assume the first thing observed is
 * discriminating), checking the recognition state after every visit:
 *
 * - [RecognitionResult.Recognized] (unique match) → stop, report it.
 * - [RecognitionResult.Ambiguous] or [RecognitionResult.Unknown] → keep
 *   visiting the next cell; neither is a final answer on its own, only
 *   "not resolved yet."
 * - Every cell visited with still nothing uniquely recognized → the whole
 *   city has been toured and no taught one explains it, so [labelForNewCity]
 *   is taught as a new one in [memory].
 *
 * Every cell in the grid is visited, not just landmarks: a real explorer
 * doesn't know in advance which cells hold a [MapFeature] worth recording
 * — that's discovered by visiting, exactly like [CitySensorModule] mirrors
 * a real Monty `SensorModule`'s per-observation reporting. Touring the
 * whole grid stays cheap regardless, since [CitySensorModule] reports an
 * empty cell as `passMessage = false` — one lookup, never touching the
 * LM's evidence.
 */
class CityAutoExplorer(
    private val cityMap: CityMap,
    private val memory: GraphMemory,
    private val lmId: String = "city-lm",
    private val random: Random = Random.Default,
) {
    sealed class Outcome {
        /** [cellsVisited] is how many cells it took before the match became unique — never more than the grid's own cell count. */
        data class Recognized(
            val label: String,
            val confidence: Float,
            val cellsVisited: Int,
        ) : Outcome()

        /** The whole grid was toured with nothing uniquely recognized, so [label] was just taught into [memory] as a new city. */
        data class Added(
            val label: String,
            val cellsVisited: Int,
        ) : Outcome()
    }

    fun explore(labelForNewCity: String): Outcome {
        val sensor = CitySensorModule(sensorId = "$lmId-sensor", cityMap = cityMap)
        val explorer = CityExplorer(sensor, EvidenceGraphLM(lmId = lmId, memory = memory))
        val cells = allCells().shuffled(random)

        explorer.beginExploration()
        for ((index, cell) in cells.withIndex()) {
            explorer.visit(cell)
            val result = explorer.currentResult()
            if (result is RecognitionResult.Recognized) {
                explorer.endExploration()
                return Outcome.Recognized(result.label, result.confidence, cellsVisited = index + 1)
            }
            // Ambiguous or Unknown: not resolved yet, keep moving.
        }

        explorer.endExploration()
        explorer.teach(labelForNewCity)
        return Outcome.Added(labelForNewCity, cellsVisited = cells.size)
    }

    private fun allCells(): List<MapLocation> =
        (0 until cityMap.size).flatMap { x -> (0 until cityMap.size).map { y -> MapLocation(x, y) } }
}
