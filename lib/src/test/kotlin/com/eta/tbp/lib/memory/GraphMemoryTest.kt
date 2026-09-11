package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.PrimitiveFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphMemoryTest {
    private fun node(
        id: Int,
        x: Float,
        y: Float,
        feature: PrimitiveFeature = PrimitiveFeature(label = "line", angle = 0f, extent = 1f),
    ) = GraphNode(id, FloatLocation(x, y), feature)

    private fun staircase(jitter: Float = 0f): List<GraphNode> =
        listOf(
            node(0, -1f + jitter, 0f),
            node(1, 0f, 0f, PrimitiveFeature(label = "arc", angle = 0f, extent = 1f)),
            node(2, 1f, 1f + jitter),
        )

    private fun modelOf(
        label: String,
        nodes: List<GraphNode>,
    ) = GraphObjectModel(label, nodes, edgeChainOf(nodes))

    @Test
    fun `teaching a new label creates its first variant`() {
        val memory = GraphMemory()
        memory.addOrMerge(modelOf("a", staircase()), "a")

        assertEquals(setOf("a"), memory.allLabels())
        assertEquals(1, memory.candidatesForLabel("a").size)
    }

    @Test
    fun `teaching a near-identical shape under the same label merges instead of duplicating`() {
        val memory = GraphMemory()
        memory.addOrMerge(modelOf("a", staircase()), "a")
        memory.addOrMerge(modelOf("a", staircase(jitter = 0.01f)), "a")

        assertEquals(1, memory.candidatesForLabel("a").size)
    }

    @Test
    fun `teaching a structurally different shape under the same label spawns a second variant`() {
        val memory = GraphMemory()
        memory.addOrMerge(modelOf("a", staircase()), "a")

        val differentShape =
            listOf(
                node(0, -1f, -1f, PrimitiveFeature(label = "arc", angle = 0f, extent = 1f)),
                node(1, 1f, 1f, PrimitiveFeature(label = "line", angle = 0f, extent = 1f)),
            )
        memory.addOrMerge(modelOf("a", differentShape), "a")

        assertEquals(2, memory.candidatesForLabel("a").size)
    }

    @Test
    fun `detectNewObject is true for a label with no stored variants`() {
        val memory = GraphMemory()
        assertTrue(memory.detectNewObject(modelOf("a", staircase()), "a"))
    }

    @Test
    fun `merging averages a variant's node locations and features toward the new exemplar`() {
        val memory = GraphMemory()
        memory.addOrMerge(modelOf("a", staircase()), "a")
        memory.addOrMerge(modelOf("a", staircase(jitter = 0.1f)), "a")

        val merged = memory.candidatesForLabel("a").single()
        val mergedFirstNode = merged.nodes.first().location as FloatLocation
        // Averaging jitter=0 and jitter=0.1's first node (x = -1 and -0.9) should land in between, not snap to either.
        assertTrue(mergedFirstNode.location[0] > -1f && mergedFirstNode.location[0] < -0.9f)
    }

    @Test
    fun `snapshot and restore round-trip the stored models`() {
        val memory = GraphMemory()
        memory.addOrMerge(modelOf("a", staircase()), "a")

        val restored = GraphMemory()
        restored.restore(memory.snapshot())

        assertEquals(memory.allLabels(), restored.allLabels())
        assertEquals(memory.candidatesForLabel("a").size, restored.candidatesForLabel("a").size)
    }
}
