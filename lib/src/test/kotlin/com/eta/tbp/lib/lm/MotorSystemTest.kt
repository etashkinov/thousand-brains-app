package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpGoal
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.FloatLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MotorSystemTest {
    private val origin = FloatLocation(5f, 5f)
    private val up = FloatLocation(5f, 4f)
    private val down = FloatLocation(5f, 6f)
    private val left = FloatLocation(4f, 5f)
    private val right = FloatLocation(6f, 5f)

    /** A 4-neighbor grid, same shape as [com.eta.tbp.lib.sensor.GridEnvironment.adjacentLocations], with no bounds check — this class doesn't know or care about grid edges, only whichever list [adjacentLocations] hands it. */
    private fun crossNeighbors(): (Location) -> List<Location> =
        { location ->
            if (location ==
                origin
            ) {
                listOf(up, down, left, right)
            } else {
                emptyList()
            }
        }

    private fun goal(
        location: Location,
        passMessage: Boolean = true,
    ) = CmpGoal(
        location = location,
        feature = null,
        confidence = 1f,
        passMessage = passMessage,
        senderId = "lm-0",
        senderType = SenderType.GSG,
        processFeaturesInLm = true,
        goalTolerances = null,
    )

    @Test
    fun `prefers the unvisited neighbor closest to a usable goal`() {
        val motorSystem = MotorSystem(crossNeighbors())
        // "right" is nearer FloatLocation(9f, 5f) than any of the other three neighbors.
        val next = motorSystem.nextLocation(origin, goals = listOf(goal(FloatLocation(9f, 5f))), visited = emptySet())
        assertEquals(right, next)
    }

    @Test
    fun `falls back to the first neighbor when there are no goals`() {
        val motorSystem = MotorSystem(crossNeighbors())
        val next = motorSystem.nextLocation(origin, goals = emptyList(), visited = emptySet())
        assertEquals(up, next)
    }

    @Test
    fun `falls back to the first neighbor when the goal's passMessage is false`() {
        val motorSystem = MotorSystem(crossNeighbors())
        val next = motorSystem.nextLocation(origin, goals = listOf(goal(right, passMessage = false)), visited = emptySet())
        assertEquals(up, next)
    }

    @Test
    fun `never returns an already-visited neighbor, goal or not`() {
        val motorSystem = MotorSystem(crossNeighbors())
        val next = motorSystem.nextLocation(origin, goals = listOf(goal(right)), visited = setOf(up, right, left))
        assertEquals(down, next)
    }

    @Test
    fun `with a nonzero positionTolerance, a neighbor near an already-visited location is treated as visited too`() {
        val motorSystem = MotorSystem(crossNeighbors(), positionTolerance = 0.3f)
        val next = motorSystem.nextLocation(origin, goals = emptyList(), visited = setOf(FloatLocation(5f, 4.1f), left, right, down))
        assertNull(next)
    }

    @Test
    fun `with a zero positionTolerance, a neighbor near but not exactly an already-visited location is still usable`() {
        val motorSystem = MotorSystem(crossNeighbors(), positionTolerance = 0f)
        val next = motorSystem.nextLocation(origin, goals = emptyList(), visited = setOf(FloatLocation(5f, 4.1f), left, right, down))
        assertEquals(up, next)
    }

    @Test
    fun `backtracks to the previous location once every neighbor of the current one is visited`() {
        val motorSystem = MotorSystem(crossNeighbors())
        // Moving away from origin (all its neighbors now visited) should backtrack to origin itself.
        val first = motorSystem.nextLocation(origin, goals = emptyList(), visited = emptySet())
        assertEquals(up, first)

        val next = motorSystem.nextLocation(up, goals = emptyList(), visited = setOf(origin, up, down, left, right))
        assertEquals(origin, next)
    }

    @Test
    fun `null once the backtrack stack is exhausted too -- nothing reachable left unvisited`() {
        val motorSystem = MotorSystem(crossNeighbors())
        val next = motorSystem.nextLocation(origin, goals = emptyList(), visited = setOf(up, down, left, right))
        assertNull(next)
    }
}
