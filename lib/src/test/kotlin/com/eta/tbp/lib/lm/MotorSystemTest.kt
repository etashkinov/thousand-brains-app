package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpGoal
import com.eta.tbp.lib.cmp.SenderType
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.FloatLocation
import org.junit.Assert.assertEquals
import org.junit.Test

class MotorSystemTest {
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
    fun `prefers an unvisited goal's location over the random fallback`() {
        val motorSystem = MotorSystem(randomLocation = { FloatLocation(9f, 9f) })
        val next = motorSystem.nextLocation(goals = listOf(goal(FloatLocation(1f, 1f))), visited = emptySet())
        assertEquals(FloatLocation(1f, 1f), next)
    }

    @Test
    fun `falls back to randomLocation when there are no goals`() {
        val motorSystem = MotorSystem(randomLocation = { FloatLocation(9f, 9f) })
        val next = motorSystem.nextLocation(goals = emptyList(), visited = emptySet())
        assertEquals(FloatLocation(9f, 9f), next)
    }

    @Test
    fun `falls back to randomLocation when the goal's passMessage is false`() {
        val motorSystem = MotorSystem(randomLocation = { FloatLocation(9f, 9f) })
        val next = motorSystem.nextLocation(goals = listOf(goal(FloatLocation(1f, 1f), passMessage = false)), visited = emptySet())
        assertEquals(FloatLocation(9f, 9f), next)
    }

    @Test
    fun `falls back to randomLocation when the goal's location is already visited`() {
        val motorSystem = MotorSystem(randomLocation = { FloatLocation(9f, 9f) })
        val next =
            motorSystem.nextLocation(
                goals = listOf(goal(FloatLocation(1f, 1f))),
                visited = setOf(FloatLocation(1f, 1f)),
            )
        assertEquals(FloatLocation(9f, 9f), next)
    }

    @Test
    fun `retries randomLocation past already-visited candidates`() {
        val candidates = listOf(FloatLocation(1f, 1f), FloatLocation(1f, 1f), FloatLocation(2f, 2f)).iterator()
        val motorSystem = MotorSystem(randomLocation = candidates::next)
        val next = motorSystem.nextLocation(goals = emptyList(), visited = setOf(FloatLocation(1f, 1f)))
        assertEquals(FloatLocation(2f, 2f), next)
    }
}
