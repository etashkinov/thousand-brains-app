package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.memory.GraphMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CityAutoExplorerTest {
    private fun springfield(origin: MapLocation) =
        CityMap.of(
            size = 10,
            (origin.x to origin.y) to "post_office",
            (origin.x + 2 to origin.y + 1) to "park",
            (origin.x + 1 to origin.y + 3) to "bakery",
        )

    private fun springfieldCells(origin: MapLocation): List<MapLocation> =
        listOf(origin, MapLocation(origin.x + 2, origin.y + 1), MapLocation(origin.x + 1, origin.y + 3))

    private fun shelbyville(origin: MapLocation) =
        CityMap.of(
            size = 10,
            (origin.x to origin.y) to "post_office",
            (origin.x + 2 to origin.y + 1) to "park",
            (origin.x + 1 to origin.y - 3) to "bakery",
        )

    private fun shelbyvilleCells(origin: MapLocation): List<MapLocation> =
        listOf(origin, MapLocation(origin.x + 2, origin.y + 1), MapLocation(origin.x + 1, origin.y - 3))

    /** Deliberately visits every one of [cells] (unlike [CityAutoExplorer], which stops as soon as it's confident) and teaches [label] — how a second city sharing landmarks with an already-known one has to be taught, so its own distinguishing cell is guaranteed to be observed first. */
    private fun teachManually(
        cityMap: CityMap,
        cells: List<MapLocation>,
        label: String,
        memory: GraphMemory,
    ) {
        val explorer =
            CityExplorer(
                CitySensorModule(sensorId = "teach-sensor", cityMap = cityMap),
                EvidenceGraphLM(lmId = "teach-lm", memory = memory),
            )
        explorer.beginExploration()
        cells.forEach { explorer.visit(it) }
        explorer.endExploration()
        explorer.teach(label)
    }

    @Test
    fun `an unfamiliar city is toured in full and taught as a new one`() {
        val memory = GraphMemory()
        val autoExplorer = CityAutoExplorer(springfield(origin = MapLocation(1, 1)), memory, random = Random(1))

        val outcome = autoExplorer.explore(labelForNewCity = "Springfield")

        assertTrue("expected Added but was $outcome", outcome is CityAutoExplorer.Outcome.Added)
        assertEquals("Springfield", (outcome as CityAutoExplorer.Outcome.Added).label)
        assertEquals(100, outcome.cellsVisited) // every cell of the 10x10 grid
        assertEquals(setOf("Springfield"), memory.allLabels())
    }

    @Test
    fun `a known city replanted elsewhere in the grid is recognized regardless of random visit order`() {
        val memory = GraphMemory()
        CityAutoExplorer(springfield(origin = MapLocation(1, 1)), memory, random = Random(1))
            .explore(labelForNewCity = "Springfield")

        // Same relative layout, moved to a different part of the grid, explored with a different random seed (different visit order).
        val outcome =
            CityAutoExplorer(springfield(origin = MapLocation(6, 5)), memory, random = Random(42))
                .explore(labelForNewCity = "should not be used")

        assertTrue("expected Recognized but was $outcome", outcome is CityAutoExplorer.Outcome.Recognized)
        assertEquals("Springfield", (outcome as CityAutoExplorer.Outcome.Recognized).label)
        assertEquals(setOf("Springfield"), memory.allLabels())
    }

    @Test
    fun `two cities sharing landmarks are told apart once the distinguishing cell is visited`() {
        val memory = GraphMemory()
        teachManually(springfield(origin = MapLocation(1, 1)), springfieldCells(MapLocation(1, 1)), "Springfield", memory)
        teachManually(shelbyville(origin = MapLocation(1, 1)), shelbyvilleCells(MapLocation(1, 1)), "Shelbyville", memory)

        // Springfield's own layout again, elsewhere in the grid: shares post office + park with Shelbyville,
        // but only Springfield's bakery position matches once that cell is reached.
        val outcome =
            CityAutoExplorer(springfield(origin = MapLocation(6, 1)), memory, random = Random(7))
                .explore(labelForNewCity = "should not be used")

        assertTrue("expected Recognized but was $outcome", outcome is CityAutoExplorer.Outcome.Recognized)
        assertEquals("Springfield", (outcome as CityAutoExplorer.Outcome.Recognized).label)
        assertEquals(setOf("Springfield", "Shelbyville"), memory.allLabels())
    }
}
