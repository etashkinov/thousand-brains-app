package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.RecognitionResult
import com.eta.tbp.lib.memory.GraphMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end coverage of the "detect city" scenario from TBP: an explorer
 * moves cell to cell (not necessarily adjacent) in an unfamiliar city,
 * comparing what they observe against every previously taught city, until
 * either a unique match or a confident no-match emerges. Every piece here —
 * [CityExplorer], [CitySensorModule], [MapFeature], [MapLocation] — is a
 * thin domain plug-in; the actual matching/evidence logic is exactly
 * [com.eta.tbp.lib.memory.GraphMatcher]/[com.eta.tbp.lib.memory.GraphMemory]/
 * [EvidenceGraphLM], unmodified from what the digit-stroke tier uses.
 */
class CityExplorerTest {
    private fun newExplorer(
        cityMap: CityMap,
        memory: GraphMemory = GraphMemory(),
    ): CityExplorer {
        val sensor = CitySensorModule(sensorId = "city-sensor", cityMap = cityMap)
        val lm = EvidenceGraphLM(lmId = "city-lm", memory = memory)
        return CityExplorer(sensor, lm)
    }

    private fun explore(
        explorer: CityExplorer,
        cells: List<MapLocation>,
    ): RecognitionResult {
        explorer.beginExploration()
        cells.forEach { explorer.visit(it) }
        return explorer.endExploration()
    }

    /** Springfield: a post office, park, and bakery at a fixed relative layout, planted at [origin] in a 10x10 grid. */
    private fun springfield(origin: MapLocation) =
        CityMap.of(
            size = 10,
            (origin.x to origin.y) to "post_office",
            (origin.x + 2 to origin.y + 1) to "park",
            (origin.x + 1 to origin.y + 3) to "bakery",
        )

    private fun springfieldCells(origin: MapLocation): List<MapLocation> =
        listOf(origin, MapLocation(origin.x + 2, origin.y + 1), MapLocation(origin.x + 1, origin.y + 3))

    /** Shelbyville: the same post office/park layout as Springfield, but its bakery sits on the opposite side. */
    private fun shelbyville(origin: MapLocation) =
        CityMap.of(
            size = 10,
            (origin.x to origin.y) to "post_office",
            (origin.x + 2 to origin.y + 1) to "park",
            (origin.x + 1 to origin.y - 3) to "bakery",
        )

    private fun shelbyvilleCells(origin: MapLocation): List<MapLocation> =
        listOf(origin, MapLocation(origin.x + 2, origin.y + 1), MapLocation(origin.x + 1, origin.y - 3))

    /** A city sharing no landmark labels with Springfield/Shelbyville at all — genuinely nothing taught explains it. */
    private fun capitalCity(origin: MapLocation) =
        CityMap.of(
            size = 10,
            (origin.x to origin.y) to "school",
            (origin.x + 3 to origin.y + 1) to "hospital",
            (origin.x + 1 to origin.y + 2) to "cafe",
        )

    private fun capitalCityCells(origin: MapLocation): List<MapLocation> =
        listOf(origin, MapLocation(origin.x + 3, origin.y + 1), MapLocation(origin.x + 1, origin.y + 2))

    @Test
    fun `exploring before anything is taught reports Unknown`() {
        val explorer = newExplorer(springfield(origin = MapLocation(1, 1)))
        val result = explore(explorer, springfieldCells(MapLocation(1, 1)))
        assertEquals(RecognitionResult.Unknown, result)
    }

    @Test
    fun `after teaching, the same city explored from a different starting cell and visit order is Recognized`() {
        val memory = GraphMemory()
        val teachExplorer = newExplorer(springfield(origin = MapLocation(1, 1)), memory)
        explore(teachExplorer, springfieldCells(MapLocation(1, 1)))
        teachExplorer.teach("Springfield")

        // Same city, but the explorer arrives at a different cell (they have
        // no way to know their true coordinate in the taught map) and visits
        // the same three landmarks in a different order.
        val recognizeExplorer = newExplorer(springfield(origin = MapLocation(4, 5)), memory)
        val result = explore(recognizeExplorer, springfieldCells(MapLocation(4, 5)).reversed())

        assertTrue("expected Recognized but was $result", result is RecognitionResult.Recognized)
        assertEquals("Springfield", (result as RecognitionResult.Recognized).label)
    }

    @Test
    fun `a genuinely different city layout is Unknown, and can be taught as a new city`() {
        val memory = GraphMemory()
        val teachExplorer = newExplorer(springfield(origin = MapLocation(1, 1)), memory)
        explore(teachExplorer, springfieldCells(MapLocation(1, 1)))
        teachExplorer.teach("Springfield")

        val capitalCityExplorer = newExplorer(capitalCity(origin = MapLocation(2, 4)), memory)
        val unknownResult = explore(capitalCityExplorer, capitalCityCells(MapLocation(2, 4)))
        assertEquals(RecognitionResult.Unknown, unknownResult)

        capitalCityExplorer.teach("Capital City")
        assertEquals(setOf("Springfield", "Capital City"), memory.allLabels())
    }

    @Test
    fun `two cities that agree on every visited cell but one stay Ambiguous until that cell is visited`() {
        val memory = GraphMemory()
        newExplorer(springfield(origin = MapLocation(1, 1)), memory)
            .also { explore(it, springfieldCells(MapLocation(1, 1))) }
            .teach("Springfield")
        newExplorer(shelbyville(origin = MapLocation(1, 1)), memory)
            .also { explore(it, shelbyvilleCells(MapLocation(1, 1))) }
            .teach("Shelbyville")

        // Visiting only the post office and park -- identical in both taught
        // cities -- doesn't distinguish them: keep investigating.
        val explorer = newExplorer(springfield(origin = MapLocation(6, 1)), memory)
        explorer.beginExploration()
        explorer.visit(MapLocation(6, 1))
        explorer.visit(MapLocation(8, 2))
        val ambiguous = explorer.endExploration()
        assertTrue("expected Ambiguous but was $ambiguous", ambiguous is RecognitionResult.Ambiguous)
        assertEquals(setOf("Springfield", "Shelbyville"), (ambiguous as RecognitionResult.Ambiguous).labels.toSet())

        // Visiting the bakery too -- at Springfield's relative position -- resolves it uniquely.
        val resolved = explore(explorer, springfieldCells(MapLocation(6, 1)))
        assertTrue("expected Recognized but was $resolved", resolved is RecognitionResult.Recognized)
        assertEquals("Springfield", (resolved as RecognitionResult.Recognized).label)
    }
}
