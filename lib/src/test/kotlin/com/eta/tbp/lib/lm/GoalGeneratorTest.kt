package com.eta.tbp.lib.lm

import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.memory.GraphNode
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.memory.edgeChainOf
import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.PrimitiveFeature
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [suggestGoalLocation] is domain-agnostic — proved here with the same
 * continuous, non-city [FloatLocation]/[PrimitiveFeature] fixtures the rest
 * of the generic kernel tests use, not [com.eta.tbp.lib.city.MapLocation].
 * [com.eta.tbp.lib.city.CityAutoExplorer] covers the city-flavored
 * end-to-end scenario separately.
 */
class GoalGeneratorTest {
    private fun node(
        x: Float,
        y: Float,
        label: String,
    ) = GraphNode(id = 0, location = FloatLocation(x, y), feature = PrimitiveFeature(label = label, angle = 0f, extent = 1f))

    private fun teach(
        memory: GraphMemory,
        label: String,
        nodes: List<GraphNode>,
    ) = memory.addOrMerge(GraphObjectModel(label, nodes, edgeChainOf(nodes)), label)

    @Test
    fun `suggests the location where two tied hypotheses disagree`() {
        val memory = GraphMemory()
        teach(memory, "L", listOf(node(0f, 0f, "line"), node(0f, 1f, "line"), node(1f, 1f, "arc")))
        teach(memory, "L2", listOf(node(0f, 0f, "line"), node(0f, 1f, "line"), node(1f, -1f, "arc")))

        val observed = listOf(node(50f, 50f, "line"), node(50f, 51f, "line"))
        val checked: Set<Location> = setOf(FloatLocation(50f, 50f), FloatLocation(50f, 51f))

        val suggestion = suggestGoalLocation(memory, tiedLabels = listOf("L", "L2"), observedNodes = observed, checkedLocations = checked)

        assertTrue(
            "expected one candidate's own predicted arc location but was $suggestion",
            suggestion == FloatLocation(51f, 51f) || suggestion == FloatLocation(51f, 49f),
        )
    }

    @Test
    fun `returns null with fewer than two tied labels`() {
        val memory = GraphMemory()
        teach(memory, "L", listOf(node(0f, 0f, "line")))

        val suggestion = suggestGoalLocation(memory, tiedLabels = listOf("L"), observedNodes = emptyList(), checkedLocations = emptySet())

        assertNull(suggestion)
    }

    @Test
    fun `returns null once every predicted location has already been checked`() {
        val memory = GraphMemory()
        teach(memory, "L", listOf(node(0f, 0f, "line"), node(0f, 1f, "line"), node(1f, 1f, "arc")))
        teach(memory, "L2", listOf(node(0f, 0f, "line"), node(0f, 1f, "line"), node(1f, -1f, "arc")))

        val observed = listOf(node(50f, 50f, "line"), node(50f, 51f, "line"))
        val checked: Set<Location> =
            setOf(FloatLocation(50f, 50f), FloatLocation(50f, 51f), FloatLocation(51f, 51f), FloatLocation(51f, 49f))

        val suggestion = suggestGoalLocation(memory, tiedLabels = listOf("L", "L2"), observedNodes = observed, checkedLocations = checked)

        assertNull(suggestion)
    }
}
