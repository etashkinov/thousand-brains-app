package com.eta.tbp.lib.lm

import com.eta.tbp.lib.sensor.RawPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PrimitiveGraphLMTest {
    private fun straightLine(
        length: Float = 30f,
        pointCount: Int = 20,
    ): List<RawPoint> = List(pointCount) { i -> RawPoint(i * length / (pointCount - 1), 0f) }

    private fun semicircle(
        radius: Float = 30f,
        pointCount: Int = 20,
    ): List<RawPoint> =
        List(pointCount) { i ->
            val angle = PI * i / (pointCount - 1)
            RawPoint(radius * cos(angle).toFloat(), radius * sin(angle).toFloat())
        }

    @Test
    fun `evaluate reports no evidence before anything is taught`() {
        val lm = PrimitiveGraphLM()
        assertEquals(emptyMap<String, Float>(), lm.evaluate(straightLine()))
    }

    @Test
    fun `a fresh line scores its own taught label highest`() {
        val lm = PrimitiveGraphLM()
        lm.teach("line", straightLine())
        lm.teach("arc", semicircle())

        val evidence = lm.evaluate(straightLine(length = 25f))
        assertTrue("expected both labels scored: $evidence", evidence.keys == setOf("line", "arc"))
        assertEquals("line", evidence.maxByOrNull { it.value }?.key)
        assertTrue("expected a strong line match but was ${evidence["line"]}", evidence.getValue("line") > 0.9f)
    }

    @Test
    fun `a fresh arc scores its own taught label highest`() {
        val lm = PrimitiveGraphLM()
        lm.teach("line", straightLine())
        lm.teach("arc", semicircle())

        val evidence = lm.evaluate(semicircle(radius = 40f))
        assertEquals("arc", evidence.maxByOrNull { it.value }?.key)
        assertTrue("expected a strong arc match but was ${evidence["arc"]}", evidence.getValue("arc") > 0.9f)
    }

    @Test
    fun `matching is scale-invariant -- a much longer line still matches a short taught line`() {
        val lm = PrimitiveGraphLM()
        lm.teach("line", straightLine(length = 10f))

        val evidence = lm.evaluate(straightLine(length = 500f))
        assertTrue("expected a strong match despite the scale difference but was ${evidence["line"]}", evidence.getValue("line") > 0.9f)
    }

    @Test
    fun `matching is direction-tolerant -- a line drawn in reverse still matches`() {
        val lm = PrimitiveGraphLM()
        lm.teach("line", straightLine())

        val reversed = straightLine().reversed()
        val evidence = lm.evaluate(reversed)
        assertTrue("expected a strong match on a reversed line but was ${evidence["line"]}", evidence.getValue("line") > 0.9f)
    }

    @Test
    fun `teaching multiple examples of the same label keeps the best match, not an average`() {
        val lm = PrimitiveGraphLM()
        lm.teach("line", straightLine(length = 10f))
        lm.teach("line", straightLine(length = 200f))

        // Both taught examples are lines at very different scales; since
        // matching is scale-invariant, a fresh line of yet another length
        // should still match "line" strongly via whichever taught variant
        // it's closest to, not be penalized for the taught set's own
        // internal length spread (there's no averaging across variants).
        val evidence = lm.evaluate(straightLine(length = 60f))
        assertTrue("expected a strong match but was ${evidence["line"]}", evidence.getValue("line") > 0.9f)
    }

    @Test
    fun `evaluate does not mutate state, unlike teach`() {
        // Regression test for a real design bug caught before wiring this
        // in: evaluate() gets called many times per replay for purely
        // speculative candidate windows a segmentation search is still
        // trying out, so it must be a pure function -- if it had any
        // "remember what I last saw" side effect (as an earlier draft did),
        // that state would end up reflecting whatever candidate window a
        // search happened to check last, not what a caller actually meant
        // to teach.
        val lm = PrimitiveGraphLM()
        lm.evaluate(straightLine())
        lm.evaluate(semicircle())
        lm.evaluate(straightLine(length = 999f))
        assertTrue(lm.allLabels().isEmpty())
    }

    @Test
    fun `allLabels reflects every taught label`() {
        val lm = PrimitiveGraphLM()
        assertTrue(lm.allLabels().isEmpty())

        lm.teach("line", straightLine())
        assertEquals(setOf("line"), lm.allLabels())

        lm.teach("arc", semicircle())
        assertEquals(setOf("line", "arc"), lm.allLabels())
    }
}
