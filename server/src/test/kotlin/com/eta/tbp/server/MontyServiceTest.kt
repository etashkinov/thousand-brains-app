package com.eta.tbp.server

import com.eta.tbp.lib.lm.ExperimentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MontyServiceTest {
    private val springfield =
        listOf(
            LandmarkInput(row = 0, col = 0, label = "City Hall"),
            LandmarkInput(row = 2, col = 2, label = "Park"),
        )

    @Test
    fun `evaluating before anything is taught reports NoMatch`() {
        val service = MontyService()

        val result = runExperiment(service, ExperimentMode.EVALUATE, "Springfield", springfield)

        assertEquals("NoMatch", result.outcome)
        assertEquals(null, result.label)
    }

    @Test
    fun `training when nothing matches teaches the city`() {
        val service = MontyService()

        val result = runExperiment(service, ExperimentMode.TRAIN, "Springfield", springfield)

        assertEquals("Taught", result.outcome)
        assertEquals("Springfield", result.label)
    }

    @Test
    fun `training then evaluating the same city recognizes it`() {
        val service = MontyService()
        runExperiment(service, ExperimentMode.TRAIN, "Springfield", springfield)

        val result = runExperiment(service, ExperimentMode.EVALUATE, "Springfield", springfield)

        assertEquals("Recognized", result.outcome)
        assertEquals("Springfield", result.label)
        assertEquals(1f, result.confidence)
    }

    @Test
    fun `training writes the taught city into memory`() {
        val service = MontyService()

        runExperiment(service, ExperimentMode.TRAIN, "Springfield", springfield)

        val snapshot = service.memorySnapshot()
        assertEquals(setOf("Springfield"), snapshot.keys)
        assertEquals(
            2,
            snapshot
                .getValue("Springfield")
                .single()
                .nodes.size,
        )
    }

    @Test
    fun `evaluating never writes memory`() {
        val service = MontyService()

        runExperiment(service, ExperimentMode.EVALUATE, "Springfield", springfield)

        assertTrue(service.memorySnapshot().isEmpty())
    }

    @Test
    fun `visited cells cover the whole grid when nothing is recognized yet, each with a distinct step`() {
        val service = MontyService()

        val result = runExperiment(service, ExperimentMode.EVALUATE, "Springfield", springfield)

        assertEquals(result.locationsVisited, result.visitedCells.size)
        val allPositions = (0 until 5).flatMap { row -> (0 until 5).map { col -> row to col } }
        assertEquals(allPositions.toSet(), result.visitedCells.map { it.row to it.col }.toSet())
        assertEquals((1..result.visitedCells.size).toSet(), result.visitedCells.map { it.step }.toSet())
    }

    @Test
    fun `reset clears everything taught`() {
        val service = MontyService()
        runExperiment(service, ExperimentMode.TRAIN, "Springfield", springfield)

        service.reset()

        assertTrue(service.memorySnapshot().isEmpty())
    }

    private fun runExperiment(
        service: MontyService,
        mode: ExperimentMode,
        cityName: String,
        landmarks: List<LandmarkInput>,
    ) = service.runExperiment(
        ExperimentRequest(
            mode = mode,
            cityName = cityName,
            citySize = 5,
            landmarks = landmarks,
        ),
    )
}
