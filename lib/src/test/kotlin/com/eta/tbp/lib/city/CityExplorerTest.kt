package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.Explorer
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.sensor.FloatLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * End-to-end coverage of the "detect city" scenario from TBP: an explorer
 * moves cell to cell (not necessarily adjacent) in an unfamiliar city,
 * comparing what they observe against every previously taught city, until
 * either a unique match or a confident no-match emerges. Every piece here —
 * [Explorer], [CitySensorModule], [MapFeature], [CityMap] — is a thin domain
 * plug-in; the actual matching/evidence logic is exactly
 * [com.eta.tbp.lib.memory.GraphMatcher]/[com.eta.tbp.lib.memory.GraphMemory]/
 * [EvidenceGraphLM], unmodified from what the digit-stroke tier uses.
 * [springfield]/[shelbyville]/[capitalCity] (`Cities.kt`) are shared with
 * [CityExperimentTest] rather than each file keeping its own copy.
 */
class CityExplorerTest {
    private fun newExplorer(
        cityMap: CityMap,
        seedState: Map<String, List<GraphObjectModel>> = emptyMap(),
    ): Explorer {
        val sensor = CitySensorModule(sensorId = "city-sensor", cityMap = cityMap)
        val lm = EvidenceGraphLM(lmId = "city-lm")
        return Explorer(sensor, lm).apply { loadState(seedState) }
    }

    /**
     * Teaches every landmark [cityMap] defines as [label] — visits them all
     * regardless of an early [RecognitionResult.Recognized] against
     * something already taught (unlike [CityExperiment], which stops as
     * soon as it's confident), the way a second city sharing landmarks with
     * an already-known one has to be taught so its own distinguishing cell
     * is guaranteed to be observed.
     */
    private fun teach(
        cityMap: CityMap,
        label: String,
        seedState: Map<String, List<GraphObjectModel>> = emptyMap(),
    ): Map<String, List<GraphObjectModel>> {
        val explorer = newExplorer(cityMap, seedState)
        explorer.explore(cityMap.cells.keys, everything = true)
        explorer.teach(label)
        return explorer.state()
    }

    @Test
    fun `exploring before anything is taught reports Unknown`() {
        val cityMap = springfield(origin = FloatLocation(1f, 1f))
        val result = newExplorer(cityMap).explore(cityMap.cells.keys, random = Random(1)).result
        assertEquals(RecognitionResult.Unknown, result)
    }

    @Test
    fun `after teaching, the same city explored from a different starting cell and visit order is Recognized`() {
        val afterTeaching = teach(springfield(origin = FloatLocation(1f, 1f)), "Springfield")

        // Same city, but the explorer arrives at a different cell (they have
        // no way to know their true coordinate in the taught map) and visits
        // the landmarks in a different order (a different random seed).
        val cityMap = springfield(origin = FloatLocation(4f, 5f))
        val result = newExplorer(cityMap, afterTeaching).explore(cityMap.cells.keys, random = Random(2)).result

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        assertEquals("Springfield", (result as RecognitionResult.Recognized).label)
    }

    @Test
    fun `a genuinely different city layout is Unknown, and can be taught as a new city`() {
        val afterSpringfield = teach(springfield(origin = FloatLocation(1f, 1f)), "Springfield")

        val cityMap = capitalCity(origin = FloatLocation(2f, 4f))
        val capitalCityExplorer = newExplorer(cityMap, afterSpringfield)
        val unknownResult = capitalCityExplorer.explore(cityMap.cells.keys, random = Random(3)).result
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
        val outcome = newExplorer(cityMap, afterBoth).explore(cityMap.cells.keys, random = Random(4))

        assertTrue("expected Recognized but was ${outcome.result}", outcome.result is RecognitionResult.Recognized)
        assertEquals("Springfield", (outcome.result as RecognitionResult.Recognized).label)
    }
}
