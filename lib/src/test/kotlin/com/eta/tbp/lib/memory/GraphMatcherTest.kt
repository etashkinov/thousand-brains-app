package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphMatcherTest {
    private fun node(
        id: Int,
        x: Float,
        y: Float,
        angle: Float,
        measurement: PrimitiveMeasurement = PrimitiveMeasurement.Line(1f),
    ) = GraphNode(id, floatArrayOf(x, y), angle, measurement)

    /** A simple 3-node "staircase": line, arc, line. */
    private fun staircase(): List<GraphNode> =
        listOf(
            node(0, -1f, 0f, 0f, PrimitiveMeasurement.Line(1f)),
            node(1, 0f, 0f, 1.5f, PrimitiveMeasurement.Arc(1f, 1f)),
            node(2, 1f, 1f, 0.2f, PrimitiveMeasurement.Line(1f)),
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
                node(0, 5f, 5f, 3f, PrimitiveMeasurement.Line(1f)),
                node(1, -5f, -5f, -2f, PrimitiveMeasurement.Arc(1f, 1f)),
                node(2, 8f, -3f, 1f, PrimitiveMeasurement.Line(1f)),
            )
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(farOff))
        // Angle, position and size error are averaged, not multiplied, so a
        // position-only catastrophic mismatch caps out well above zero (here,
        // size still matches perfectly by construction) — what actually
        // matters is staying clearly under GraphMemory.MERGE_THRESHOLD (0.75),
        // not near zero.
        assertTrue("expected a score well below the merge threshold but was $score", score < 0.75f)
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
    fun `a candidate with the same shape but different-length lines scores below an exact size match`() {
        // Same positions, angles, and primitive-type sequence -- only the
        // reported line lengths differ. Before PrimitiveMeasurement existed,
        // a short line and a long line pointing the same way were
        // indistinguishable to GraphMatcher; this is the regression test
        // that the size dimension actually affects the score now.
        val nodes = staircase()
        val exactMatch = staircase()
        val differentLengths =
            listOf(
                node(0, -1f, 0f, 0f, PrimitiveMeasurement.Line(5f)),
                node(1, 0f, 0f, 1.5f, PrimitiveMeasurement.Arc(5f, 5f)),
                node(2, 1f, 1f, 0.2f, PrimitiveMeasurement.Line(5f)),
            )

        val exactScore = GraphMatcher.matchScore(modelOf(nodes), modelOf(exactMatch))
        val mismatchedSizeScore = GraphMatcher.matchScore(modelOf(nodes), modelOf(differentLengths))

        assertTrue(
            "expected a size mismatch to score lower than an exact match: $mismatchedSizeScore vs $exactScore",
            mismatchedSizeScore < exactScore,
        )
    }

    @Test
    fun `a candidate with the same shape but a different-radius arc scores below an exact size match`() {
        // Same regression as the line-length test above, for the other
        // measurement variant: an arc's radius is a real size component
        // alongside its sweep angle (see PrimitiveMeasurement.Arc's class
        // doc), so a radius mismatch alone should also cost score.
        val nodes = staircase()
        val exactMatch = staircase()
        val differentRadius =
            listOf(
                node(0, -1f, 0f, 0f, PrimitiveMeasurement.Line(1f)),
                node(1, 0f, 0f, 1.5f, PrimitiveMeasurement.Arc(sweepAngle = 1f, radius = 5f)),
                node(2, 1f, 1f, 0.2f, PrimitiveMeasurement.Line(1f)),
            )

        val exactScore = GraphMatcher.matchScore(modelOf(nodes), modelOf(exactMatch))
        val mismatchedRadiusScore = GraphMatcher.matchScore(modelOf(nodes), modelOf(differentRadius))

        assertTrue(
            "expected a radius mismatch to score lower than an exact match: $mismatchedRadiusScore vs $exactScore",
            mismatchedRadiusScore < exactScore,
        )
    }

    @Test
    fun `bestAlignedWindow reorders stored nodes to match the candidate's own order`() {
        val nodes = staircase()
        val reversedCandidate = modelOf(nodes.reversed())
        val window = GraphMatcher.bestAlignedWindow(modelOf(nodes), reversedCandidate)
        assertEquals(nodes.reversed().map { it.measurement }, window?.map { it.measurement })
    }
}
