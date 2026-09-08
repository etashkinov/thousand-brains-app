package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.lm.PrimitiveGraphLM
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [PrimitiveSensorModule]'s own [SensorModule] contract (buffering
 * in [PrimitiveSensorModule.step], draining in [PrimitiveSensorModule.flushStroke],
 * resetting in [PrimitiveSensorModule.preEpisode]) directly. The actual
 * segmentation/matching *behavior* against real drawn shapes is already
 * covered end to end by `MontyOrchestratorTest`, which exercises this class
 * through the real orchestrator — no need to duplicate those cases here.
 */
class PrimitiveSensorModuleTest {
    private fun canonicalLine(pointCount: Int = 15): List<RawPoint> = List(pointCount) { i -> RawPoint(i.toFloat(), i.toFloat()) }

    private fun observationsFor(
        points: List<RawPoint>,
        strokeIndex: Int = 0,
    ): List<RawTouchObservation> = StrokePreprocessor.preprocess(points, strokeIndex = strokeIndex)

    private fun newModule(): PrimitiveSensorModule {
        val primitiveGraphLM = PrimitiveGraphLM()
        primitiveGraphLM.teach("line", canonicalLine())
        return PrimitiveSensorModule(sensorId = "test-sensor", primitiveGraphLM = primitiveGraphLM)
    }

    @Test
    fun `step buffers the observation and returns a passMessage=false placeholder`() {
        val module = newModule()
        val observation = observationsFor(canonicalLine()).first()

        val message = module.step(observation)

        assertFalse(message.passMessage)
    }

    @Test
    fun `flushStroke drains everything stepped since the last flush as real messages`() {
        val module = newModule()
        observationsFor(canonicalLine()).forEach { module.step(it) }

        val messages = module.flushStroke(strokeIndex = 0)

        assertTrue(messages.isNotEmpty())
        assertTrue(messages.all { it.passMessage })
    }

    @Test
    fun `flushStroke with nothing stepped since the last flush returns no messages`() {
        val module = newModule()

        assertTrue(module.flushStroke(strokeIndex = 0).isEmpty())
    }

    @Test
    fun `flushStroke only drains observations stepped since the previous flush, not accumulated forever`() {
        val module = newModule()
        observationsFor(canonicalLine(), strokeIndex = 0).forEach { module.step(it) }
        module.flushStroke(strokeIndex = 0)

        observationsFor(canonicalLine(), strokeIndex = 1).forEach { module.step(it) }
        val secondFlush = module.flushStroke(strokeIndex = 1)

        assertTrue(secondFlush.isNotEmpty())
        assertTrue(secondFlush.all { (it.nonMorphologicalFeatures as PrimitiveFeatures).strokeIndex == 1 })
    }

    @Test
    fun `preEpisode clears any buffered observations`() {
        val module = newModule()
        observationsFor(canonicalLine()).forEach { module.step(it) }

        module.preEpisode()

        assertTrue(module.flushStroke(strokeIndex = 0).isEmpty())
    }
}
