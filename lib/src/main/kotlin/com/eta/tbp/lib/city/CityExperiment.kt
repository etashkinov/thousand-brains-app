package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.ExperimentMode
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.log.Logger
import com.eta.tbp.lib.memory.GraphObjectModel
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
 * [lm]'s memory, regardless of outcome; [train] always resolves to either
 * an existing city or a newly taught one, never a bare miss.
 *
 * Every cell in the grid is a *candidate* to visit, not just landmarks: a
 * real explorer doesn't know in advance which cells hold a [MapFeature]
 * worth recording — that's discovered by visiting, exactly like
 * [CitySensorModule] mirrors a real Monty `SensorModule`'s per-observation
 * reporting. Touring stays cheap regardless of how much of the grid ends
 * up visited, since [CitySensorModule] reports an empty cell as
 * `passMessage = false` — one lookup, never touching the LM's evidence.
 *
 * [logger] defaults to [Logger.None] (silent) and, when set, is handed down
 * to every [CitySensorModule]/[EvidenceGraphLM]/[CityExplorer] this class
 * wires up — one [Logger] for the whole pipeline, each layer tagging its
 * own events.
 *
 * [sensor]/[lm]/[explorer] are built exactly once, in this class's own
 * initializer, and reused by every [train]/[evaluate] call on this
 * instance — mirroring real Monty's own `Monty` object, which is
 * constructed once per experiment and persists across every episode
 * (`MontyExperiment.pre_episode()` calls `self.model.reset()`, it never
 * recreates `self.model`). [runEpisode] relies on [CityExplorer.beginExploration]/
 * [CityExplorer.endExploration] (which forward to [lm]/[sensor]'s own
 * `preEpisode()`/`postEpisode()`) to clear per-episode-only state between
 * calls — [lm]'s taught cities are never touched by that reset, exactly
 * like [lm]'s own doc describes for [EvidenceGraphLM.preEpisode].
 *
 * [lm] owns and constructs its own [com.eta.tbp.lib.memory.GraphMemory]
 * internally (see [EvidenceGraphLM]'s own doc) rather than this class
 * building one and injecting it — moving previously taught cities into a
 * fresh [CityExperiment] (e.g. a new "session" recognizing what an earlier
 * one taught) goes through [state]/[loadState] instead, the same
 * checkpoint-restore idiom real Monty uses to load a pretrained model.
 */
class CityExperiment(
    private val cityMap: CityMap,
    private val lmId: String = "city-lm",
    private val random: Random = Random.Default,
    private val logger: Logger = Logger.Console,
) {
    private val sensor = CitySensorModule(sensorId = "$lmId-sensor", cityMap = cityMap, logger = logger)
    private val lm = EvidenceGraphLM(lmId = lmId, logger = logger)
    private val explorer = CityExplorer(sensor, lm, logger = logger)

    sealed class Outcome {
        /** [cellsVisited] is how many cells it took before the match became unique — never more than the grid's own cell count. */
        data class Recognized(
            val label: String,
            val confidence: Float,
            val cellsVisited: Int,
        ) : Outcome()

        /** [evaluate] only: the whole grid was toured and nothing matched — nothing was taught. */
        data class NoMatch(
            val cellsVisited: Int,
        ) : Outcome()

        /** [train] only: the whole grid was toured and nothing matched, so [label] was just taught as a new city. */
        data class Taught(
            val label: String,
            val cellsVisited: Int,
        ) : Outcome()
    }

    /** Explores [cityMap] and, if nothing already taught uniquely matches, teaches [label] as a new city — real Monty's TRAIN behavior (a known ground-truth label, supplied by the caller the same way a labeled dataset entry supplies one). */
    fun train(label: String): Outcome {
        logger.info(TAG) { "train('$label') starting on a ${cityMap.size}x${cityMap.size} grid" }
        val cellsVisited = runEpisode(ExperimentMode.TRAIN)
        val result = explorer.currentResult()
        val outcome =
            if (result is RecognitionResult.Recognized) {
                Outcome.Recognized(result.label, result.confidence, cellsVisited)
            } else {
                explorer.teach(label) // Belt-and-suspenders: the real gate is EvidenceGraphLM's own ExperimentMode check.
                Outcome.Taught(label, cellsVisited)
            }
        logger.info(TAG) { "train('$label') finished: $outcome" }
        return outcome
    }

    /** Explores [cityMap] purely to check it against what's already known — never writes to [lm]'s memory, matching real Monty's EVALUATE behavior. */
    fun evaluate(): Outcome {
        logger.info(TAG) { "evaluate() starting on a ${cityMap.size}x${cityMap.size} grid" }
        val cellsVisited = runEpisode(ExperimentMode.EVALUATE)
        val result = explorer.currentResult()
        val outcome =
            if (result is RecognitionResult.Recognized) {
                Outcome.Recognized(result.label, result.confidence, cellsVisited)
            } else {
                Outcome.NoMatch(cellsVisited)
            }
        logger.info(TAG) { "evaluate() finished: $outcome" }
        return outcome
    }

    /** [lm]'s own taught cities — for moving them into another [CityExperiment] instance (a fresh "session" recognizing what an earlier one taught), the same checkpoint-restore idiom real Monty uses to load a pretrained model. */
    fun state(): Map<String, List<GraphObjectModel>> = lm.state()

    /** Loads previously taught cities (from [state]) into [lm]. */
    fun loadState(state: Map<String, List<GraphObjectModel>>) = lm.loadState(state)

    /** The shared step loop [train]/[evaluate] both run on [explorer] — only [mode] differs between them. */
    private fun runEpisode(mode: ExperimentMode): Int {
        lm.setExperimentMode(mode)
        val remainingCells = allCells().shuffled(random).toMutableList()

        explorer.beginExploration()
        var cellsVisited = 0
        while (remainingCells.isNotEmpty()) {
            val suggestion = explorer.suggestNextLocation()?.takeIf { it in remainingCells }
            val nextCell = suggestion ?: remainingCells.first()
            remainingCells.remove(nextCell)

            logger.debug(TAG) { "step ${cellsVisited + 1}: visiting $nextCell (${if (suggestion != null) "goal-suggested" else "random"})" }
            explorer.visit(nextCell)
            cellsVisited++

            if (explorer.currentResult() is RecognitionResult.Recognized) break
            // Ambiguous or Unknown: not resolved yet, keep moving.
        }

        explorer.endExploration()
        return cellsVisited
    }

    private fun allCells(): List<MapLocation> =
        (0 until cityMap.size).flatMap { x -> (0 until cityMap.size).map { y -> MapLocation(x, y) } }

    private companion object {
        const val TAG = "CityExperiment"
    }
}
