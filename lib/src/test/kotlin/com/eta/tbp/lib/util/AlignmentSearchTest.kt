package com.eta.tbp.lib.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentSearchTest {
    /** A trivial node type — just an Int "value" — so this test is independent of any real graph/point schema. */
    private fun exactMatchScore(
        window: List<Int>,
        target: List<Int>,
    ): Float = if (window == target) 1f else 0f

    @Test
    fun `an empty stored sequence scores zero with an empty window`() {
        val result = AlignmentSearch.bestAlignment(emptyList(), listOf(1, 2), ::exactMatchScore)
        assertEquals(0f, result.score, 1e-6f)
        assertEquals(emptyList<Int>(), result.window)
    }

    @Test
    fun `finds the matching window regardless of starting offset`() {
        val stored = listOf(1, 2, 3, 4)
        // Rotated so the "natural" [1,2,3] sequence starts at a different offset in `stored`.
        val target = listOf(3, 4, 1)
        val result = AlignmentSearch.bestAlignment(stored, target, ::exactMatchScore)
        assertEquals(1f, result.score, 1e-6f)
        assertEquals(target, result.window)
    }

    @Test
    fun `finds the matching window when the target is reversed`() {
        val stored = listOf(1, 2, 3, 4)
        val reversedTarget = listOf(4, 3, 2, 1)
        val result = AlignmentSearch.bestAlignment(stored, reversedTarget, ::exactMatchScore)
        assertEquals(1f, result.score, 1e-6f)
        assertEquals(reversedTarget, result.window)
    }

    @Test
    fun `returns the highest-scoring alignment when nothing matches exactly`() {
        val stored = listOf(1, 2, 3)
        // A scorer that rewards how many positions happen to line up exactly.
        val overlapScore: (
            List<Int>,
            List<Int>,
        ) -> Float = { window, target -> window.zip(target).count { it.first == it.second }.toFloat() }
        val target = listOf(2, 3, 9)
        val result = AlignmentSearch.bestAlignment(stored, target, overlapScore)
        assertTrue("expected a positive best score but was ${result.score}", result.score > 0f)
        assertEquals(listOf(2, 3, 1), result.window)
    }

    @Test
    fun `a shorter target searches for windows of its own length`() {
        val stored = listOf(1, 2, 3, 4, 5)
        val target = listOf(4, 5)
        val result = AlignmentSearch.bestAlignment(stored, target, ::exactMatchScore)
        assertEquals(2, result.window.size)
        assertEquals(target, result.window)
    }
}
