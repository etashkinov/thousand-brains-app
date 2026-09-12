package com.eta.tbp.lib.city

import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.log.CollectingLogger
import com.eta.tbp.lib.memory.GraphObjectModel
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

    /**
     * Deliberately visits every one of [cells] (unlike [CityExperiment], which stops as soon as
     * it's confident) and teaches [label] — how a second city sharing landmarks with an
     * already-known one has to be taught, so its own distinguishing cell is guaranteed to be
     * observed first. [priorState] seeds the explorer's own [EvidenceGraphLM] before teaching
     * (e.g. a second call teaching a further label alongside an earlier one); the returned state
     * is what a caller feeds into the next [teachManually] call, or into a [CityExperiment] via
     * [CityExperiment.loadState].
     */
    private fun teachManually(
        cityMap: CityMap,
        cells: List<MapLocation>,
        label: String,
        priorState: Map<String, List<GraphObjectModel>> = emptyMap(),
    ): Map<String, List<GraphObjectModel>> {
        val explorer =
            CityExplorer(
                CitySensorModule(sensorId = "teach-sensor", cityMap = cityMap),
                EvidenceGraphLM(lmId = "teach-lm"),
            ).apply { loadState(priorState) }
        explorer.beginExploration()
        cells.forEach { explorer.visit(it) }
        explorer.endExploration()
        explorer.teach(label)
        return explorer.state()
    }

    @Test
    fun `train tours an unfamiliar city in full and teaches it as a new one`() {
        val experiment = CityExperiment(springfield(origin = MapLocation(1, 1)), random = Random(1))

        val outcome = experiment.train("Springfield")

        assertTrue("expected Taught but was $outcome", outcome is CityExperiment.Outcome.Taught)
        assertEquals("Springfield", (outcome as CityExperiment.Outcome.Taught).label)
        assertEquals(100, outcome.cellsVisited) // every cell of the 10x10 grid
        assertEquals(setOf("Springfield"), experiment.state().keys)
    }

    @Test
    fun `evaluate tours an unfamiliar city in full and reports NoMatch without touching memory`() {
        val experiment = CityExperiment(springfield(origin = MapLocation(1, 1)), random = Random(1))

        val outcome = experiment.evaluate()

        assertTrue("expected NoMatch but was $outcome", outcome is CityExperiment.Outcome.NoMatch)
        assertEquals(100, (outcome as CityExperiment.Outcome.NoMatch).cellsVisited)
        assertEquals(emptySet<String>(), experiment.state().keys)
    }

    @Test
    fun `a known city replanted elsewhere in the grid is recognized regardless of random visit order`() {
        val teachExperiment = CityExperiment(springfield(origin = MapLocation(1, 1)), random = Random(1))
        teachExperiment.train("Springfield")

        // Same relative layout, moved to a different part of the grid, explored with a different
        // random seed (different visit order) — a fresh "session" loading what the first one taught.
        val evalExperiment = CityExperiment(springfield(origin = MapLocation(6, 5)), random = Random(42))
        evalExperiment.loadState(teachExperiment.state())
        val outcome = evalExperiment.evaluate()

        assertTrue("expected Recognized but was $outcome", outcome is CityExperiment.Outcome.Recognized)
        assertEquals("Springfield", (outcome as CityExperiment.Outcome.Recognized).label)
        assertEquals(setOf("Springfield"), evalExperiment.state().keys)
    }

    @Test
    fun `a shared logger receives events from every layer of the pipeline`() {
        val logger = CollectingLogger()
        val experiment = CityExperiment(springfield(origin = MapLocation(1, 1)), random = Random(1), logger = logger)

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
        val afterSpringfield = teachManually(springfield(origin = MapLocation(1, 1)), springfieldCells(MapLocation(1, 1)), "Springfield")
        val afterBoth =
            teachManually(
                shelbyville(origin = MapLocation(1, 1)),
                shelbyvilleCells(MapLocation(1, 1)),
                "Shelbyville",
                afterSpringfield,
            )

        // Springfield's own layout again, elsewhere in the grid: shares post office + park with Shelbyville,
        // but only Springfield's bakery position matches once that cell is reached.
        val experiment = CityExperiment(springfield(origin = MapLocation(6, 1)), random = Random(7))
        experiment.loadState(afterBoth)
        val outcome = experiment.evaluate()

        assertTrue("expected Recognized but was $outcome", outcome is CityExperiment.Outcome.Recognized)
        assertEquals("Springfield", (outcome as CityExperiment.Outcome.Recognized).label)
        assertEquals(setOf("Springfield", "Shelbyville"), experiment.state().keys)
    }
}
