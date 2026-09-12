package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.ExperimentMode
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.memory.GraphMemory
import kotlin.random.Random

/**
 * Automates the "detect city" loop end to end, per TBP's own framing: an
 * explorer who finds themselves in [cityMap] doesn't know their own
 * coordinate on arrival, so each episode starts by visiting cells in a
 * random order (not just a random first cell — see
 * [GraphMatcher][com.eta.tbp.lib.memory.GraphMatcher]'s doc for why anchoring
 * can't assume the first thing observed is discriminating), checking the
 * recognition state after every visit:
 *
 * - [RecognitionResult.Recognized] (unique match) → stop, report it.
 * - [RecognitionResult.Ambiguous] (multiple known cities still fit) → ask
 *   the LM (via [CityExplorer.suggestNextLocation], its own embedded Goal
 *   State Generator — see [com.eta.tbp.lib.lm.EvidenceGraphLM.suggestNextLocation])
 *   where visiting next would best tell the tied candidates apart, rather
 *   than picking blindly. Real Monty does the same once its own hypotheses
 *   narrow.
 * - [RecognitionResult.Unknown], or no suggestion available → nothing to
 *   disambiguate between yet, so just visit the next random cell.
 *
 * [train] and [evaluate] mirror `MontyExperiment.train()`/`.evaluate()`, not
 * one method with a mode flag: they diverge in exactly one place, whether
 * the whole-grid-toured-with-nothing-recognized case teaches a new city or
 * just reports the miss. That divergence exists in real Monty too, and
 * exactly this way — see [EvidenceGraphLM.teach]'s own doc for the
 * `update_ltm_from_stm` gate this mirrors. [evaluate] never writes to
 * [memory], regardless of outcome; [train] always resolves to either an
 * existing city or a newly taught one, never a bare miss.
 *
 * Every cell in the grid is a *candidate* to visit, not just landmarks: a
 * real explorer doesn't know in advance which cells hold a [MapFeature]
 * worth recording — that's discovered by visiting, exactly like
 * [CitySensorModule] mirrors a real Monty `SensorModule`'s per-observation
 * reporting. Touring stays cheap regardless of how much of the grid ends
 * up visited, since [CitySensorModule] reports an empty cell as
 * `passMessage = false` — one lookup, never touching the LM's evidence.
 */
class CityExperiment(
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

        /** [evaluate] only: the whole grid was toured and nothing matched — [memory] was left untouched. */
        data class NoMatch(
            val cellsVisited: Int,
        ) : Outcome()

        /** [train] only: the whole grid was toured and nothing matched, so [label] was just taught into [memory] as a new city. */
        data class Taught(
            val label: String,
            val cellsVisited: Int,
        ) : Outcome()
    }

    /** Explores [cityMap] and, if nothing already in [memory] uniquely matches, teaches [label] as a new city — real Monty's TRAIN behavior (a known ground-truth label, supplied by the caller the same way a labeled dataset entry supplies one). */
    fun train(label: String): Outcome {
        val (explorer, cellsVisited) = runEpisode(ExperimentMode.TRAIN)
        val result = explorer.currentResult()
        if (result is RecognitionResult.Recognized) return Outcome.Recognized(result.label, result.confidence, cellsVisited)
        explorer.teach(label) // Belt-and-suspenders: the real gate is EvidenceGraphLM's own ExperimentMode check.
        return Outcome.Taught(label, cellsVisited)
    }

    /** Explores [cityMap] purely to check it against what's already known — never writes to [memory], matching real Monty's EVALUATE behavior. */
    fun evaluate(): Outcome {
        val (explorer, cellsVisited) = runEpisode(ExperimentMode.EVALUATE)
        val result = explorer.currentResult()
        return if (result is RecognitionResult.Recognized) {
            Outcome.Recognized(result.label, result.confidence, cellsVisited)
        } else {
            Outcome.NoMatch(cellsVisited)
        }
    }

    /** The shared step loop [train]/[evaluate] both run — only [mode] (via the [EvidenceGraphLM] it wires up) differs between them. */
    private fun runEpisode(mode: ExperimentMode): Pair<CityExplorer, Int> {
        val sensor = CitySensorModule(sensorId = "$lmId-sensor", cityMap = cityMap)
        val lm = EvidenceGraphLM(lmId = lmId, memory = memory).apply { setExperimentMode(mode) }
        val explorer = CityExplorer(sensor, lm)
        val remainingCells = allCells().shuffled(random).toMutableList()

        explorer.beginExploration()
        var cellsVisited = 0
        while (remainingCells.isNotEmpty()) {
            val nextCell = explorer.suggestNextLocation()?.takeIf { it in remainingCells } ?: remainingCells.first()
            remainingCells.remove(nextCell)

            explorer.visit(nextCell)
            cellsVisited++

            if (explorer.currentResult() is RecognitionResult.Recognized) break
            // Ambiguous or Unknown: not resolved yet, keep moving.
        }

        explorer.endExploration()
        return explorer to cellsVisited
    }

    private fun allCells(): List<MapLocation> =
        (0 until cityMap.size).flatMap { x -> (0 until cityMap.size).map { y -> MapLocation(x, y) } }
}
