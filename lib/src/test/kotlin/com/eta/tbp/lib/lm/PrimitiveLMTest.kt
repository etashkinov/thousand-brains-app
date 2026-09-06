package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.StrokePreprocessor
import com.eta.tbp.lib.sensor.TouchSensorModule
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class PrimitiveLMTest {
    private val sensor = TouchSensorModule(sensorId = "touch-0")

    /** Drives a whole synthetic stroke through Sensor -> PrimitiveLM, per the orchestrator's per-point loop. */
    private fun drivePrimitives(points: List<RawPoint>): List<CmpMessage> {
        val lm = PrimitiveLM(lmId = "primitive-0")
        val observations = StrokePreprocessor.preprocess(points, strokeIndex = 0)

        lm.preEpisode()
        val emitted = mutableListOf<CmpMessage>()
        for (observation in observations) {
            lm.matchingStep(listOf(sensor.step(observation)))
            drain(lm, emitted)
        }
        lm.postEpisode()
        drain(lm, emitted)
        return emitted
    }

    private fun drain(
        lm: PrimitiveLM,
        into: MutableList<CmpMessage>,
    ) {
        while (true) {
            val vote = lm.sendOutVote()
            if (!vote.passMessage) break
            into += vote
        }
    }

    private fun primitiveTypesOf(messages: List<CmpMessage>): List<String> =
        messages.map { (it.nonMorphologicalFeatures as PrimitiveFeatures).type.name.lowercase() }

    @Test
    fun `a straight line segments into a single line primitive`() {
        val line = List(40) { i -> RawPoint(i.toFloat(), i.toFloat()) }
        val primitives = drivePrimitives(line)
        assertEquals(listOf("line"), primitiveTypesOf(primitives))
    }

    @Test
    fun `an L shape segments into line, corner, line`() {
        // Straight down, then a sharp single-point corner, then straight right.
        val down = List(20) { i -> RawPoint(0f, i.toFloat()) }
        val right = List(20) { i -> RawPoint(i.toFloat(), 19f) }
        val lShape = down + right.drop(1)

        val primitives = drivePrimitives(lShape)

        assertEquals(listOf("line", "corner", "line"), primitiveTypesOf(primitives))
    }

    @Test
    fun `a semicircle segments into a single arc primitive`() {
        val semicircle =
            List(40) { i ->
                val angle = PI * i / 39
                RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
            }
        val primitives = drivePrimitives(semicircle)
        assertEquals(listOf("arc"), primitiveTypesOf(primitives))
    }

    @Test
    fun `state is stateless and experiment mode does not throw`() {
        val lm = PrimitiveLM(lmId = "primitive-0")
        assertEquals(Unit, lm.state())
        lm.setExperimentMode(ExperimentMode.TRAIN)
        lm.loadState(Unit)
    }
}
