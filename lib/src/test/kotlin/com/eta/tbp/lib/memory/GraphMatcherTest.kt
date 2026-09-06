package com.eta.tbp.lib.memory

import com.eta.tbp.lib.lm.PrimitiveType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphMatcherTest {
    private fun node(
        id: Int,
        x: Float,
        y: Float,
        angle: Float,
        type: PrimitiveType = PrimitiveType.LINE,
    ) = GraphNode(id, floatArrayOf(x, y), angle, type)

    /** A simple 3-node "staircase": line, corner, line. */
    private fun staircase(): List<GraphNode> =
        listOf(
            node(0, -1f, 0f, 0f, PrimitiveType.LINE),
            node(1, 0f, 0f, 1.5f, PrimitiveType.CORNER),
            node(2, 1f, 1f, 0.2f, PrimitiveType.LINE),
        )

    private fun modelOf(nodes: List<GraphNode>) = GraphObjectModel("x", nodes, edgeChainOf(nodes), exemplarCount = 1)

    @Test
    fun `identical graphs score near 1`() {
        val nodes = staircase()
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(nodes))
        assertTrue("expected near 1.0 but was $score", score > 0.99f)
    }

    @Test
    fun `same graph starting from a different node scores near 1`() {
        val nodes = staircase()
        val rotated = nodes.drop(1) + nodes.take(1)
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(rotated))
        assertTrue("expected near 1.0 but was $score", score > 0.99f)
    }

    @Test
    fun `same graph traversed in reverse scores near 1`() {
        val nodes = staircase()
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(nodes.reversed()))
        assertTrue("expected near 1.0 but was $score", score > 0.99f)
    }

    @Test
    fun `different primitive type sequence scores zero`() {
        val nodes = staircase()
        val differentTypes = listOf(node(0, -1f, 0f, 0f), node(1, 0f, 0f, 1.5f), node(2, 1f, 1f, 0.2f))
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(differentTypes))
        assertEquals(0f, score, 1e-6f)
    }

    @Test
    fun `very different angles and positions score well below the merge threshold`() {
        val nodes = staircase()
        val farOff =
            listOf(
                node(0, 5f, 5f, 3f, PrimitiveType.LINE),
                node(1, -5f, -5f, -2f, PrimitiveType.CORNER),
                node(2, 8f, -3f, 1f, PrimitiveType.LINE),
            )
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(farOff))
        // Angle and position error are averaged, not multiplied, so a position-only
        // catastrophic mismatch caps out well above zero — what actually matters is
        // staying clearly under GraphMemory.MERGE_THRESHOLD (0.75), not near zero.
        assertTrue("expected a score well below the merge threshold but was $score", score < 0.5f)
    }

    @Test
    fun `mismatched node counts never match`() {
        val nodes = staircase()
        val shorter = nodes.take(2)
        assertEquals(0f, GraphMatcher.matchScore(modelOf(nodes), modelOf(shorter)), 1e-6f)
    }

    @Test
    fun `partialMatchScore tracks a growing prefix of the same shape`() {
        val nodes = staircase()
        val partial = nodes.take(2)
        val score = GraphMatcher.partialMatchScore(modelOf(nodes), partial)
        assertTrue("expected near 1.0 but was $score", score > 0.99f)
    }

    @Test
    fun `partialMatchScore is zero when the candidate is longer than the stored model`() {
        val nodes = staircase().take(2)
        val longerPartial = staircase()
        assertEquals(0f, GraphMatcher.partialMatchScore(modelOf(nodes), longerPartial), 1e-6f)
    }

    @Test
    fun `bestAlignedWindow reorders stored nodes to match the candidate's own order`() {
        val nodes = staircase()
        val reversedCandidate = modelOf(nodes.reversed())
        val window = GraphMatcher.bestAlignedWindow(modelOf(nodes), reversedCandidate)
        assertEquals(nodes.reversed().map { it.primitiveType }, window?.map { it.primitiveType })
    }
}
