package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.Explorer
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.Environment
import com.eta.tbp.lib.sensor.EnvironmentSensorModule
import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.GridEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * End-to-end coverage of the "detect city" scenario from TBP: an explorer
 * compares what they observe against every previously taught city, until
 * either a unique match or a confident no-match emerges. Every piece here —
 * [Explorer], [com.eta.tbp.lib.sensor.EnvironmentSensorModule], [com.eta.tbp.lib.memory.LabelFeature], [GridEnvironment] — is a thin domain
 * plug-in; the actual matching/evidence logic is exactly
 * [com.eta.tbp.lib.memory.GraphMatcher]/[com.eta.tbp.lib.memory.GraphMemory]/
 * [EvidenceGraphLM], unmodified from what the digit-stroke tier uses.
 * [springfield]/[shelbyville]/[capitalCity] (`Cities.kt`) are shared with
 * [CityExperimentTest] rather than each file keeping its own copy.
 *
 * Drives [Explorer] directly ([Explorer.visit], not [Explorer.explore]) over
 * only [GridEnvironment.cells] (the taught landmarks), in a shuffled order —
 * unlike [com.eta.tbp.lib.experiment.Experiment], whose own [Explorer.explore]
 * now walks the whole grid block by block, empty cells included, and can't
 * jump straight between landmarks that aren't adjacent. This file exercises
 * [Explorer]/[EvidenceGraphLM] wiring directly against every landmark, not a
 * realistic blind grid tour, which [CityExperimentTest] already covers.
 */
class CityExplorerTest {
    private fun newExplorer(
        seedState: Map<String, List<GraphObjectModel>> = emptyMap(),
        positionTolerance: Float = 0f,
    ): Explorer {
        val lm = EvidenceGraphLM(lmId = "city-lm", positionTolerance = positionTolerance)
        val sensor = EnvironmentSensorModule(sensorId = "city-sensor", positionTolerance = lm.positionTolerance)
        return Explorer(sensor, lm).apply { loadState(seedState) }
    }

    /** Every one of [cityMap]'s own landmark cells, in a shuffled order — [runExploration]'s tour. */
    private fun shuffledLandmarks(
        cityMap: GridEnvironment,
        random: Random,
    ): List<Location> = cityMap.cells.keys.shuffled(random)

    /**
     * Like [shuffledLandmarks], but each entry is offset by a small random
     * amount (up to [maxJitter] per axis) — the "fuzzy map" scenario:
     * [Explorer.visit] lands near a landmark's taught coordinate, not
     * exactly on it, the way a real noisy sensor reading would. [maxJitter]
     * must stay comfortably under both the `positionTolerance` the probing
     * [newExplorer]'s [EvidenceGraphLM] (and, from it, its
     * [EnvironmentSensorModule]) is configured with — so
     * [GridEnvironment.featureAt] still resolves the landmark — and half
     * the map's own inter-landmark spacing (so jitter never makes one
     * landmark's reading closer to a different landmark).
     */
    private fun jitteredLandmarks(
        cityMap: GridEnvironment,
        pickRandom: Random,
        jitterRandom: Random,
        maxJitter: Float = 0.1f,
    ): List<Location> =
        shuffledLandmarks(cityMap, pickRandom).map { cell ->
            val dx = (jitterRandom.nextFloat() * 2f - 1f) * maxJitter
            val dy = (jitterRandom.nextFloat() * 2f - 1f) * maxJitter
            cell.plus(FloatLocation(dx, dy))
        }

    /** Visits every one of [locations] in order, stopping early on [RecognitionResult.Recognized] unless [everything] is set — mirrors [Explorer.explore]'s own stopping rule, without its adjacency-constrained motor system. */
    private fun runExploration(
        explorer: Explorer,
        environment: Environment,
        locations: List<Location>,
        everything: Boolean = false,
    ): RecognitionResult {
        explorer.beginExploration()
        for (location in locations) {
            explorer.visit(environment, location)
            if (!everything && explorer.currentResult() is RecognitionResult.Recognized) break
        }
        return explorer.endExploration()
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
        val explorer = newExplorer(seedState)
        runExploration(explorer, cityMap, shuffledLandmarks(cityMap, Random(0)), everything = true)
        explorer.teach(label)
        return explorer.state()
    }

    @Test
    fun `exploring before anything is taught reports Unknown`() {
        val cityMap = springfield(origin = FloatLocation(1f, 1f))
        val result = runExploration(newExplorer(), cityMap, shuffledLandmarks(cityMap, Random(1)))
        assertEquals(RecognitionResult.Unknown, result)
    }

    @Test
    fun `after teaching, the same city explored from a different starting cell and visit order is Recognized`() {
        val afterTeaching = teach(springfield(origin = FloatLocation(1f, 1f)), "Springfield")

        // Same city, but the explorer arrives at a different cell (they have
        // no way to know their true coordinate in the taught map) and visits
        // the landmarks in a different order (a different random seed).
        val cityMap = springfield(origin = FloatLocation(4f, 5f))
        val result = runExploration(newExplorer(afterTeaching), cityMap, shuffledLandmarks(cityMap, Random(2)))

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        assertEquals("Springfield", (result as RecognitionResult.Recognized).label)
    }

    @Test
    fun `a genuinely different city layout is Unknown, and can be taught as a new city`() {
        val afterSpringfield = teach(springfield(origin = FloatLocation(1f, 1f)), "Springfield")

        val cityMap = capitalCity(origin = FloatLocation(2f, 4f))
        val capitalCityExplorer = newExplorer(afterSpringfield)
        val unknownResult = runExploration(capitalCityExplorer, cityMap, shuffledLandmarks(cityMap, Random(3)))
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
        val result = runExploration(newExplorer(afterBoth), cityMap, shuffledLandmarks(cityMap, Random(4)))

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        assertEquals("Springfield", (result as RecognitionResult.Recognized).label)
    }

    @Test
    fun `a city explored with jittered observations near each landmark is still Recognized`() {
        val afterTeaching = teach(springfield(origin = FloatLocation(1f, 1f)), "Springfield")

        val cityMap = springfield(origin = FloatLocation(4f, 5f))
        val result =
            runExploration(
                newExplorer(afterTeaching, positionTolerance = 0.3f),
                cityMap,
                jitteredLandmarks(cityMap, pickRandom = Random(5), jitterRandom = Random(6)),
            )

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        assertEquals("Springfield", (result as RecognitionResult.Recognized).label)
    }
}
