package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphMemoryTest {
    private fun node(
        id: Int,
        x: Float,
        y: Float,
        angle: Float,
        measurement: PrimitiveMeasurement = PrimitiveMeasurement(label = "line", extent = 1f),
    ) = GraphNode(id, floatArrayOf(x, y), angle, measurement)

    private fun staircase(jitter: Float = 0f): List<GraphNode> =
        listOf(
            node(0, -1f + jitter, 0f, 0f + jitter),
            node(1, 0f, 0f, 1.5f),
            node(2, 1f, 1f + jitter, 0.2f),
        )

    private fun modelOf(
        label: String,
        nodes: List<GraphNode>,
    ) = GraphObjectModel(label, nodes, edgeChainOf(nodes), exemplarCount = 1)

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

        val variants = memory.candidatesForLabel("a")
        assertEquals(1, variants.size)
        assertEquals(2, variants.first().exemplarCount)
    }

    @Test
    fun `teaching a structurally different shape under the same label spawns a second variant`() {
        val memory = GraphMemory()
        memory.addOrMerge(modelOf("a", staircase()), "a")

        val differentShape =
            listOf(
                node(0, -1f, -1f, 3f, PrimitiveMeasurement(label = "arc", extent = 1f)),
                node(1, 1f, 1f, -1f, PrimitiveMeasurement(label = "line", extent = 1f)),
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
    fun `snapshot and restore round-trip the stored models`() {
        val memory = GraphMemory()
        memory.addOrMerge(modelOf("a", staircase()), "a")

        val restored = GraphMemory()
        restored.restore(memory.snapshot())

        assertEquals(memory.allLabels(), restored.allLabels())
        assertEquals(memory.candidatesForLabel("a").size, restored.candidatesForLabel("a").size)
    }
}
