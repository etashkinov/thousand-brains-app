package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.log.CollectingLogger
import com.eta.tbp.lib.memory.GraphMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CityExperimentTest {
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

    /** Deliberately visits every one of [cells] (unlike [CityExperiment], which stops as soon as it's confident) and teaches [label] — how a second city sharing landmarks with an already-known one has to be taught, so its own distinguishing cell is guaranteed to be observed first. */
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
    fun `train tours an unfamiliar city in full and teaches it as a new one`() {
        val memory = GraphMemory()
        val experiment = CityExperiment(springfield(origin = MapLocation(1, 1)), memory, random = Random(1))

        val outcome = experiment.train("Springfield")

        assertTrue("expected Taught but was $outcome", outcome is CityExperiment.Outcome.Taught)
        assertEquals("Springfield", (outcome as CityExperiment.Outcome.Taught).label)
        assertEquals(100, outcome.cellsVisited) // every cell of the 10x10 grid
        assertEquals(setOf("Springfield"), memory.allLabels())
    }

    @Test
    fun `evaluate tours an unfamiliar city in full and reports NoMatch without touching memory`() {
        val memory = GraphMemory()
        val experiment = CityExperiment(springfield(origin = MapLocation(1, 1)), memory, random = Random(1))

        val outcome = experiment.evaluate()

        assertTrue("expected NoMatch but was $outcome", outcome is CityExperiment.Outcome.NoMatch)
        assertEquals(100, (outcome as CityExperiment.Outcome.NoMatch).cellsVisited)
        assertEquals(emptySet<String>(), memory.allLabels())
    }

    @Test
    fun `a known city replanted elsewhere in the grid is recognized regardless of random visit order`() {
        val memory = GraphMemory()
        CityExperiment(springfield(origin = MapLocation(1, 1)), memory, random = Random(1)).train("Springfield")

        // Same relative layout, moved to a different part of the grid, explored with a different random seed (different visit order).
        val outcome = CityExperiment(springfield(origin = MapLocation(6, 5)), memory, random = Random(42)).evaluate()

        assertTrue("expected Recognized but was $outcome", outcome is CityExperiment.Outcome.Recognized)
        assertEquals("Springfield", (outcome as CityExperiment.Outcome.Recognized).label)
        assertEquals(setOf("Springfield"), memory.allLabels())
    }

    @Test
    fun `a shared logger receives events from every layer of the pipeline`() {
        val memory = GraphMemory()
        val logger = CollectingLogger()
        val experiment = CityExperiment(springfield(origin = MapLocation(1, 1)), memory, random = Random(1), logger = logger)

        experiment.train("Springfield")

        val tags = logger.entries.map { it.tag }.toSet()
        assertEquals(setOf("CityExperiment", "CityExplorer", "CitySensorModule", "EvidenceGraphLM"), tags)
        assertTrue(
            "expected a 'taught' info log but got ${logger.entries}",
            logger.entries.any { it.level == "I" && it.tag == "EvidenceGraphLM" && it.message.contains("taught 'Springfield'") },
        )
    }

    @Test
    fun `two cities sharing landmarks are told apart once the distinguishing cell is visited`() {
        val memory = GraphMemory()
        teachManually(springfield(origin = MapLocation(1, 1)), springfieldCells(MapLocation(1, 1)), "Springfield", memory)
        teachManually(shelbyville(origin = MapLocation(1, 1)), shelbyvilleCells(MapLocation(1, 1)), "Shelbyville", memory)

        // Springfield's own layout again, elsewhere in the grid: shares post office + park with Shelbyville,
        // but only Springfield's bakery position matches once that cell is reached.
        val outcome = CityExperiment(springfield(origin = MapLocation(6, 1)), memory, random = Random(7)).evaluate()

        assertTrue("expected Recognized but was $outcome", outcome is CityExperiment.Outcome.Recognized)
        assertEquals("Springfield", (outcome as CityExperiment.Outcome.Recognized).label)
        assertEquals(setOf("Springfield", "Shelbyville"), memory.allLabels())
    }
}
