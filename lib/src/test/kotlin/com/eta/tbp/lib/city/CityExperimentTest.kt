package com.eta.tbp.lib.city

import com.eta.tbp.lib.experiment.Experiment
import com.eta.tbp.lib.lm.EvidenceGraphLM
import com.eta.tbp.lib.lm.Explorer
import com.eta.tbp.lib.log.CollectingLogger
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.sensor.EnvironmentSensorModule
import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.GridEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * [Experiment] no longer takes its own `random` — exploration randomness
 * lives on [GridEnvironment.randomLocation] instead (see [Environment][com.eta.tbp.lib.sensor.Environment]'s
 * own doc). Every [springfield]/[shelbyville] call below passes an explicit
 * seed for that reason: an unseeded [GridEnvironment] draws from [Random.Default],
 * which isn't fixed across runs, so assertions that depend on a particular
 * outcome (not just "some locationsVisited count") need a reproducible visit
 * order to avoid flaking.
 */
class CityExperimentTest {
    /**
     * Deliberately visits every one of [cityMap]'s own landmark cells (unlike [Experiment],
     * which stops as soon as it's confident) and teaches [label] — how a second city sharing
     * landmarks with an already-known one has to be taught, so its own distinguishing cell is
     * guaranteed to be observed first. [priorState] seeds the explorer's own [EvidenceGraphLM]
     * before teaching (e.g. a second call teaching a further label alongside an earlier one); the
     * returned state is what a caller feeds into the next [teachManually] call, or into an
     * [Experiment] via [Experiment.loadState]. [random] is seeded by the caller — same
     * reason every [GridEnvironment] fixture below takes an explicit seed, see this class's own note.
     */
    private fun teachManually(
        cityMap: GridEnvironment,
        label: String,
        priorState: Map<String, List<GraphObjectModel>> = emptyMap(),
        random: Random = Random(0),
    ): Map<String, List<GraphObjectModel>> {
        val explorer =
            Explorer(
                EnvironmentSensorModule(sensorId = "teach-sensor"),
                EvidenceGraphLM(lmId = "teach-lm"),
            ).apply { loadState(priorState) }

        // Visits every landmark directly, in a shuffled order -- not Explorer.explore's own
        // block-by-block whole-grid walk (see CityExplorerTest's own doc for why that's the
        // right call here too: this helper exercises teaching, not a realistic grid tour).
        explorer.beginExploration()
        cityMap.cells.keys
            .shuffled(random)
            .forEach { explorer.visit(cityMap, it) }
        explorer.endExploration()

        explorer.teach(label)
        return explorer.state()
    }

    @Test
    fun `train tours an unfamiliar city in full and teaches it as a new one`() {
        val experiment = Experiment(springfield(origin = FloatLocation(1f, 1f), random = Random(1)))

        val outcome = experiment.train("Springfield")

        assertTrue("expected Taught but was $outcome", outcome is Experiment.Outcome.Taught)
        assertEquals("Springfield", (outcome as Experiment.Outcome.Taught).label)
        // Every cell of the 10x10 grid was visited at least once -- not necessarily exactly
        // 100 *steps* any more, since a block-by-block walk can double back over already-walked
        // ground to reach a new block (see Explorer.explore's own doc on backtracking).
        assertEquals(100, experiment.visitedLocationsWithState().map { it.first }.toSet().size)
        assertTrue(outcome.locationsVisited >= 100)
        assertEquals(setOf("Springfield"), experiment.state().keys)
        // Exactly springfield()'s own 3 landmarks -- not more: backtracking over an
        // already-walked block necessarily revisits some cells (see the assertions
        // above), and a real landmark among them must not be taught twice over.
        assertEquals(
            3,
            experiment
                .state()
                .getValue("Springfield")
                .single()
                .nodes.size,
        )
    }

    @Test
    fun `evaluate tours an unfamiliar city in full and reports NoMatch without touching memory`() {
        val experiment = Experiment(springfield(origin = FloatLocation(1f, 1f), random = Random(1)))

        val outcome = experiment.evaluate()

        assertTrue("expected NoMatch but was $outcome", outcome is Experiment.Outcome.NoMatch)
        assertEquals(100, experiment.visitedLocationsWithState().map { it.first }.toSet().size)
        assertTrue((outcome as Experiment.Outcome.NoMatch).locationsVisited >= 100)
        assertEquals(emptySet<String>(), experiment.state().keys)
    }

    @Test
    fun `a known city replanted elsewhere in the grid is recognized regardless of random visit order`() {
        val teachExperiment = Experiment(springfield(origin = FloatLocation(1f, 1f), random = Random(1)))
        teachExperiment.train("Springfield")

        // Same relative layout, moved to a different part of the grid, explored with a different
        // random seed (different visit order) — a fresh "session" loading what the first one taught.
        val evalExperiment = Experiment(springfield(origin = FloatLocation(6f, 5f), random = Random(42)))
        evalExperiment.loadState(teachExperiment.state())
        val outcome = evalExperiment.evaluate()

        assertTrue("expected Recognized but was $outcome", outcome is Experiment.Outcome.Recognized)
        assertEquals("Springfield", (outcome as Experiment.Outcome.Recognized).label)
        assertEquals(setOf("Springfield"), evalExperiment.state().keys)
    }

    @Test
    fun `a shared logger receives events from every layer of the pipeline`() {
        val logger = CollectingLogger()
        val experiment = Experiment(springfield(origin = FloatLocation(1f, 1f), random = Random(1)), logger = logger)

        experiment.train("Springfield")

        val tags = logger.entries.map { it.tag }.toSet()
        assertEquals(setOf("Experiment", "Explorer", "EnvironmentSensorModule", "EvidenceGraphLM"), tags)
        assertTrue(
            "expected a 'taught' info log but got ${logger.entries}",
            logger.entries.any { it.level == "I" && it.tag == "EvidenceGraphLM" && it.message.contains("taught 'Springfield'") },
        )
    }

    @Test
    fun `two cities sharing landmarks are told apart once the distinguishing cell is visited`() {
        val sp = springfield(origin = FloatLocation(1f, 1f))
        val afterSpringfield =
            teachManually(sp, "Springfield")
        val afterBoth =
            teachManually(
                shelbyville(origin = FloatLocation(1f, 1f)),
                "Shelbyville",
                afterSpringfield,
            )

        // Springfield's own layout again, elsewhere in the grid: shares post office + park with Shelbyville,
        // but only Springfield's bakery position matches once that cell is reached.
        val experiment = Experiment(springfield(origin = FloatLocation(6f, 1f), random = Random(7)))
        experiment.loadState(afterBoth)
        val outcome = experiment.evaluate()

        assertTrue("expected Recognized but was $outcome", outcome is Experiment.Outcome.Recognized)
        assertEquals("Springfield", (outcome as Experiment.Outcome.Recognized).label)
        assertEquals(setOf("Springfield", "Shelbyville"), experiment.state().keys)
    }
}
