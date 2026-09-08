package com.eta.tbp.lib.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeSegmenterTest {
    @Test
    fun `an empty stroke segments to no windows`() {
        val windows = StrokeSegmenter.segment(0, minWindowLength = 4, maxWindowLength = 10, segmentPenalty = 0f) { _, _ -> 0f }
        assertEquals(emptyList<StrokeSegmenter.Window>(), windows)
    }

    @Test
    fun `windows are contiguous, ordered, and cover the whole stroke with no gaps or overlaps`() {
        val pointCount = 30
        val windows =
            StrokeSegmenter.segment(pointCount, minWindowLength = 4, maxWindowLength = 10, segmentPenalty = 0.1f) { start, end ->
                // Arbitrary but deterministic, not crafted to prefer any particular split.
                (end - start).toFloat() % 3
            }

        assertEquals(0, windows.first().startIndex)
        assertEquals(pointCount, windows.last().endIndex)
        for (i in 1 until windows.size) {
            assertEquals("window $i should start where window ${i - 1} ended", windows[i - 1].endIndex, windows[i].startIndex)
        }
        for (window in windows) {
            assertTrue("window $window should be non-empty", window.endIndex > window.startIndex)
        }
    }

    @Test
    fun `finds the exact partition a crafted score function strongly prefers`() {
        // Two "ideal" windows, [0, 12) and [12, 30) -- score them far above
        // anything else, and confirm the DP finds exactly that split rather
        // than some other combination that happens to sum similarly.
        val idealSplit = setOf(0 to 12, 12 to 30)
        val windows =
            StrokeSegmenter.segment(30, minWindowLength = 4, maxWindowLength = 20, segmentPenalty = 0.5f) { start, end ->
                if ((start to end) in idealSplit) 10f else 0.1f
            }

        assertEquals(listOf(StrokeSegmenter.Window(0, 12), StrokeSegmenter.Window(12, 30)), windows)
    }

    @Test
    fun `segmentPenalty prevents degenerate over-segmentation when every window scores equally well`() {
        // If every possible window scores exactly the same regardless of
        // length, summing MORE windows always wins whenever the per-window
        // score exceeds the penalty (more net-positive terms summed beats
        // fewer) -- exactly the "many spurious short primitives" failure
        // this algorithm exists to avoid. A penalty that exceeds the flat
        // per-window score makes each additional split net-negative, so the
        // DP should prefer the fewest windows that still cover the stroke.
        val pointCount = 24
        val windows =
            StrokeSegmenter.segment(pointCount, minWindowLength = 4, maxWindowLength = 24, segmentPenalty = 6f) { _, _ -> 5f }

        assertEquals(
            "expected the single largest possible window, not several small ones, but got $windows",
            listOf(StrokeSegmenter.Window(0, pointCount)),
            windows,
        )
    }

    @Test
    fun `splitting still wins when the score gain clearly outweighs the segment penalty`() {
        // Two strong, narrow windows beat one mediocre window spanning both,
        // even after paying the per-window penalty twice.
        val windows =
            StrokeSegmenter.segment(20, minWindowLength = 4, maxWindowLength = 20, segmentPenalty = 0.5f) { start, end ->
                if (start to end == 0 to 10 || start to end == 10 to 20) 8f else 1f
            }

        assertEquals(listOf(StrokeSegmenter.Window(0, 10), StrokeSegmenter.Window(10, 20)), windows)
    }

    @Test
    fun `no window exceeds maxWindowLength`() {
        val windows =
            StrokeSegmenter.segment(50, minWindowLength = 3, maxWindowLength = 7, segmentPenalty = 0.2f) { start, end ->
                (end - start).toFloat() // reward long windows, to actively pressure-test the cap
            }
        for (window in windows) {
            assertTrue(
                "window $window exceeds maxWindowLength",
                window.endIndex - window.startIndex <= 7,
            )
        }
    }

    @Test
    fun `interior windows respect minWindowLength, even when short windows score higher`() {
        val windows =
            StrokeSegmenter.segment(40, minWindowLength = 5, maxWindowLength = 15, segmentPenalty = 0.1f) { start, end ->
                // Actively reward SHORT windows, to pressure-test that the
                // minimum is still enforced away from the boundaries.
                1f / (end - start)
            }
        for (i in 1 until windows.size - 1) {
            val window = windows[i]
            assertTrue(
                "interior window $window is shorter than minWindowLength",
                window.endIndex - window.startIndex >= 5,
            )
        }
    }

    @Test
    fun `a stroke shorter than minWindowLength still produces one covering window`() {
        // A non-zero segmentPenalty exceeding the flat per-window score is
        // needed here too, for the same reason as the over-segmentation
        // test above -- otherwise more (boundary-exempt short) windows
        // would net-positive their way to a higher total.
        val windows = StrokeSegmenter.segment(3, minWindowLength = 5, maxWindowLength = 10, segmentPenalty = 2f) { _, _ -> 1f }
        assertEquals(listOf(StrokeSegmenter.Window(0, 3)), windows)
    }

    @Test
    fun `a single point segments to one window`() {
        val windows = StrokeSegmenter.segment(1, minWindowLength = 4, maxWindowLength = 10, segmentPenalty = 0f) { _, _ -> 1f }
        assertEquals(listOf(StrokeSegmenter.Window(0, 1)), windows)
    }

    @Test
    fun `a point count that doesn't evenly divide by the window bounds still segments successfully`() {
        // 29 points with a [5, 6] window range doesn't evenly divide --
        // this exercises the start/end boundary relaxation in
        // isAllowedWindow rather than throwing.
        val windows = StrokeSegmenter.segment(29, minWindowLength = 5, maxWindowLength = 6, segmentPenalty = 0.1f) { _, _ -> 1f }
        assertEquals(0, windows.first().startIndex)
        assertEquals(29, windows.last().endIndex)
    }

    @Test
    fun `rejects an invalid maxWindowLength smaller than minWindowLength`() {
        assertThrows(IllegalArgumentException::class.java) {
            StrokeSegmenter.segment(10, minWindowLength = 8, maxWindowLength = 5, segmentPenalty = 0f) { _, _ -> 1f }
        }
    }

    @Test
    fun `rejects a negative point count`() {
        assertThrows(IllegalArgumentException::class.java) {
            StrokeSegmenter.segment(-1, minWindowLength = 1, maxWindowLength = 5, segmentPenalty = 0f) { _, _ -> 1f }
        }
    }
}
