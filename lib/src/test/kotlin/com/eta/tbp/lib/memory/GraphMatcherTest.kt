package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.PrimitiveFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphMatcherTest {
    private fun node(
        id: Int,
        x: Float,
        y: Float,
        feature: PrimitiveFeature = PrimitiveFeature(label = "line", angle = 0f, extent = 1f),
    ) = GraphNode(id, FloatLocation(x, y), feature)

    /** A simple 3-node "staircase": line, arc, line. */
    private fun staircase(): List<GraphNode> =
        listOf(
            node(0, -1f, 0f, PrimitiveFeature(label = "line", angle = 0f, extent = 1f)),
            node(1, 0f, 0f, PrimitiveFeature(label = "arc", angle = 0f, extent = 1f)),
            node(2, 1f, 1f, PrimitiveFeature(label = "line", angle = 0f, extent = 1f)),
        )

    private fun modelOf(nodes: List<GraphNode>) = GraphObjectModel("x", nodes, edgeChainOf(nodes), exemplarCount = 1)

    @Test
    fun `identical graphs score near 1`() {
        val nodes = staircase()
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(nodes))
        assertTrue("expected near 1.0 but was $score", score > 0.99f)
    }

    @Test
    fun `a translated instance of the same graph scores near 1`() {
        val nodes = staircase()
        val translated =
            listOf(
                node(0, 49f, -20f, PrimitiveFeature(label = "line", angle = 0f, extent = 1f)),
                node(1, 50f, -20f, PrimitiveFeature(label = "arc", angle = 0f, extent = 1f)),
                node(2, 51f, -19f, PrimitiveFeature(label = "line", angle = 0f, extent = 1f)),
            )
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(translated))
        assertTrue("expected near 1.0 but was $score", score > 0.99f)
    }

    @Test
    fun `the same graph listed starting from a different node scores near 1`() {
        val nodes = staircase()
        val rotated = nodes.drop(1) + nodes.take(1)
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(rotated))
        assertTrue("expected near 1.0 but was $score", score > 0.99f)
    }

    @Test
    fun `the same graph listed in reverse order scores near 1`() {
        val nodes = staircase()
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(nodes.reversed()))
        assertTrue("expected near 1.0 but was $score", score > 0.99f)
    }

    @Test
    fun `no feature overlap at all scores zero`() {
        val nodes = staircase()
        val disjointLabels =
            listOf(
                node(0, -1f, 0f, PrimitiveFeature(label = "square", angle = 0f, extent = 1f)),
                node(1, 0f, 0f, PrimitiveFeature(label = "square", angle = 0f, extent = 1f)),
                node(2, 1f, 1f, PrimitiveFeature(label = "square", angle = 0f, extent = 1f)),
            )
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(disjointLabels))
        assertEquals(0f, score, 1e-6f)
    }

    @Test
    fun `a rearranged but still feature-compatible sequence is not automatically zero`() {
        // Permuting which position holds "line" vs "arc" doesn't itself
        // disqualify a match: an anchor pair only needs to be individually
        // feature-compatible, not the whole sequence in lockstep -- that
        // permutation tolerance is deliberate (a city explorer can visit
        // cells in any order). A genuine mismatch needs disjoint features
        // entirely (see the test above) or wildly different relative
        // positions (see the "very different relative positions" test).
        val nodes = staircase()
        val relabeled =
            listOf(
                node(0, -1f, 0f, PrimitiveFeature(label = "arc", angle = 0f, extent = 1f)),
                node(1, 0f, 0f, PrimitiveFeature(label = "line", angle = 0f, extent = 1f)),
                node(2, 1f, 1f, PrimitiveFeature(label = "arc", angle = 0f, extent = 1f)),
            )
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(relabeled))
        assertTrue("expected some nonzero score from the remaining feature-compatible pairs but was $score", score > 0f)
    }

    @Test
    fun `very different relative positions score well below the merge threshold`() {
        val nodes = staircase()
        val farOff =
            listOf(
                node(0, 5f, 5f, PrimitiveFeature(label = "line", angle = 0f, extent = 1f)),
                node(1, -5f, -5f, PrimitiveFeature(label = "arc", angle = 0f, extent = 1f)),
                node(2, 8f, -3f, PrimitiveFeature(label = "line", angle = 0f, extent = 1f)),
            )
        val score = GraphMatcher.matchScore(modelOf(nodes), modelOf(farOff))
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
    fun `a candidate with the same shape but a different extent scores below an exact size match`() {
        val nodes = staircase()
        val exactMatch = staircase()
        val differentExtent =
            listOf(
                node(0, -1f, 0f, PrimitiveFeature(label = "line", angle = 0f, extent = 5f)),
                node(1, 0f, 0f, PrimitiveFeature(label = "arc", angle = 0f, extent = 5f)),
                node(2, 1f, 1f, PrimitiveFeature(label = "line", angle = 0f, extent = 5f)),
            )

        val exactScore = GraphMatcher.matchScore(modelOf(nodes), modelOf(exactMatch))
        val mismatchedSizeScore = GraphMatcher.matchScore(modelOf(nodes), modelOf(differentExtent))

        assertTrue(
            "expected a size mismatch to score lower than an exact match: $mismatchedSizeScore vs $exactScore",
            mismatchedSizeScore < exactScore,
        )
    }

    @Test
    fun `bestAlignedWindow reorders the candidate's nodes to match the target's own order`() {
        val nodes = staircase()
        val reversedCandidate = modelOf(nodes.reversed())
        val window = GraphMatcher.bestAlignedWindow(modelOf(nodes), reversedCandidate)
        assertEquals(nodes.reversed().map { it.feature }, window?.map { it.feature })
    }

    @Test
    fun `bestAlignedWindow is null when nothing is feature-compatible`() {
        val nodes = staircase()
        val noOverlap = listOf(node(0, -1f, 0f, PrimitiveFeature(label = "unrelated", angle = 0f, extent = 1f)))
        assertEquals(null, GraphMatcher.bestAlignedWindow(modelOf(nodes), modelOf(noOverlap)))
    }
}
