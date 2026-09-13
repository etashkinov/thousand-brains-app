package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.Explorer
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.EnvironmentSensorModule
import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.GridEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * End-to-end coverage of the "detect city" scenario from TBP: an explorer
 * moves cell to cell (not necessarily adjacent) in an unfamiliar city,
 * comparing what they observe against every previously taught city, until
 * either a unique match or a confident no-match emerges. Every piece here —
 * [Explorer], [com.eta.tbp.lib.sensor.EnvironmentSensorModule], [com.eta.tbp.lib.memory.LabelFeature], [GridEnvironment] — is a thin domain
 * plug-in; the actual matching/evidence logic is exactly
 * [com.eta.tbp.lib.memory.GraphMatcher]/[com.eta.tbp.lib.memory.GraphMemory]/
 * [EvidenceGraphLM], unmodified from what the digit-stroke tier uses.
 * [springfield]/[shelbyville]/[capitalCity] (`Cities.kt`) are shared with
 * [CityExperimentTest] rather than each file keeping its own copy.
 *
 * [randomAmong] samples only [GridEnvironment.cells] (the taught landmarks) rather
 * than [com.eta.tbp.lib.experiment.Experiment]'s own whole-grid [Explorer.explore]
 * policy — this file exercises [Explorer]/[EvidenceGraphLM] wiring, not a
 * realistic blind grid tour, which [CityExperimentTest] already covers.
 */
class CityExplorerTest {
    private fun newExplorer(
        cityMap: GridEnvironment,
        seedState: Map<String, List<GraphObjectModel>> = emptyMap(),
        positionTolerance: Float = 0f,
    ): Explorer {
        val sensor = EnvironmentSensorModule(sensorId = "city-sensor", environment = cityMap)
        val lm = EvidenceGraphLM(lmId = "city-lm", positionTolerance = positionTolerance)
        return Explorer(sensor, lm).apply { loadState(seedState) }
    }

    /** A `() -> Location` that samples uniformly among [cityMap]'s own landmark cells — [Explorer.explore]'s only source of "where to look next" absent a goal suggestion. */
    private fun randomAmong(
        cityMap: GridEnvironment,
        random: Random,
    ): () -> Location = { cityMap.cells.keys.random(random) }

    /**
     * Like [randomAmong], but each pick is offset by a small random amount
     * (up to [maxJitter] per axis) — the "fuzzy map" scenario:
     * [Explorer.visit] lands near a landmark's taught coordinate, not
     * exactly on it, the way a real noisy sensor reading would. [maxJitter]
     * must stay comfortably under both [GridEnvironment.positionTolerance]
     * (so [GridEnvironment.featureAt] still resolves the landmark) and half
     * the map's own inter-landmark spacing (so jitter never makes one
     * landmark's reading closer to a different landmark).
     */
    private fun jitteredAmong(
        cityMap: GridEnvironment,
        pickRandom: Random,
        jitterRandom: Random,
        maxJitter: Float = 0.1f,
    ): () -> Location = {
        val cell = cityMap.cells.keys.random(pickRandom)
        val dx = (jitterRandom.nextFloat() * 2f - 1f) * maxJitter
        val dy = (jitterRandom.nextFloat() * 2f - 1f) * maxJitter
        cell.plus(FloatLocation(dx, dy))
    }

    /**
     * Teaches every landmark [cityMap] defines as [label] — visits them all
     * regardless of an early [RecognitionResult.Recognized] against
     * something already taught (unlike [com.eta.tbp.lib.experiment.Experiment], which
     * stops as soon as it's confident), the way a second city sharing landmarks with
     * an already-known one has to be taught so its own distinguishing cell
     * is guaranteed to be observed.
     */
    private fun teach(
        cityMap: GridEnvironment,
        label: String,
        seedState: Map<String, List<GraphObjectModel>> = emptyMap(),
    ): Map<String, List<GraphObjectModel>> {
        val explorer = newExplorer(cityMap, seedState)
        explorer.explore(randomAmong(cityMap, Random(0)), maxSteps = cityMap.cells.size, everything = true)
        explorer.teach(label)
        return explorer.state()
    }

    @Test
    fun `exploring before anything is taught reports Unknown`() {
        val cityMap = springfield(origin = FloatLocation(1f, 1f))
        val result = newExplorer(cityMap).explore(randomAmong(cityMap, Random(1)), maxSteps = cityMap.cells.size).result
        assertEquals(RecognitionResult.Unknown, result)
    }

    @Test
    fun `after teaching, the same city explored from a different starting cell and visit order is Recognized`() {
        val afterTeaching = teach(springfield(origin = FloatLocation(1f, 1f)), "Springfield")

        // Same city, but the explorer arrives at a different cell (they have
        // no way to know their true coordinate in the taught map) and visits
        // the landmarks in a different order (a different random seed).
        val cityMap = springfield(origin = FloatLocation(4f, 5f))
        val result =
            newExplorer(cityMap, afterTeaching)
                .explore(randomAmong(cityMap, Random(2)), maxSteps = cityMap.cells.size)
                .result

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        assertEquals("Springfield", (result as RecognitionResult.Recognized).label)
    }

    @Test
    fun `a genuinely different city layout is Unknown, and can be taught as a new city`() {
        val afterSpringfield = teach(springfield(origin = FloatLocation(1f, 1f)), "Springfield")

        val cityMap = capitalCity(origin = FloatLocation(2f, 4f))
        val capitalCityExplorer = newExplorer(cityMap, afterSpringfield)
        val unknownResult = capitalCityExplorer.explore(randomAmong(cityMap, Random(3)), maxSteps = cityMap.cells.size).result
        assertEquals(RecognitionResult.Unknown, unknownResult)

        capitalCityExplorer.teach("Capital City")
        assertEquals(setOf("Springfield", "Capital City"), capitalCityExplorer.state().keys)
    }

    @Test
    fun `two cities sharing landmarks are told apart once the distinguishing cell is visited`() {
        val afterSpringfield = teach(springfield(origin = FloatLocation(1f, 1f)), "Springfield")
        val afterBoth = teach(shelbyville(origin = FloatLocation(1f, 1f)), "Shelbyville", afterSpringfield)

        // Springfield's own layout again, elsewhere in the grid: shares post office + park with Shelbyville,
        // but only Springfield's bakery position matches once that cell is reached.
        val cityMap = springfield(origin = FloatLocation(6f, 1f))
        val outcome = newExplorer(cityMap, afterBoth).explore(randomAmong(cityMap, Random(4)), maxSteps = cityMap.cells.size)

        assertTrue("expected Recognized but was ${outcome.result}", outcome.result is RecognitionResult.Recognized)
        assertEquals("Springfield", (outcome.result as RecognitionResult.Recognized).label)
    }

    @Test
    fun `a city explored with jittered observations near each landmark is still Recognized`() {
        val afterTeaching = teach(springfield(origin = FloatLocation(1f, 1f)), "Springfield")

        val cityMap = springfield(origin = FloatLocation(4f, 5f))
        val outcome =
            newExplorer(cityMap, afterTeaching, positionTolerance = cityMap.positionTolerance)
                .explore(
                    jitteredAmong(cityMap, pickRandom = Random(5), jitterRandom = Random(6)),
                    maxSteps = cityMap.cells.size,
                )

        assertTrue("expected Recognized but was ${outcome.result}", outcome.result is RecognitionResult.Recognized)
        assertEquals("Springfield", (outcome.result as RecognitionResult.Recognized).label)
    }
}
