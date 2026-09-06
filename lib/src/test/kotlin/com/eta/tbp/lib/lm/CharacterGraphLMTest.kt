package com.eta.tbp.lib.lm

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.memory.GraphMemory
import com.eta.tbp.lib.sensor.RawPoint
import com.eta.tbp.lib.sensor.StrokePreprocessor
import com.eta.tbp.lib.sensor.TouchSensorModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class CharacterGraphLMTest {
    private val sensor = TouchSensorModule(sensorId = "touch-0")

    /** Drives one full stroke through Sensor -> PrimitiveLM -> CharacterGraphLM, per the orchestrator's per-point loop. */
    private fun drive(
        points: List<RawPoint>,
        primitiveLM: PrimitiveLM,
        characterGraphLM: CharacterGraphLM,
    ) {
        val observations = StrokePreprocessor.preprocess(points, strokeIndex = 0)

        primitiveLM.preEpisode()
        characterGraphLM.preEpisode()
        for (observation in observations) {
            primitiveLM.matchingStep(listOf(sensor.step(observation)))
            feedPrimitives(primitiveLM, characterGraphLM)
        }
        primitiveLM.postEpisode()
        feedPrimitives(primitiveLM, characterGraphLM)
        characterGraphLM.postEpisode()
    }

    private fun feedPrimitives(
        primitiveLM: PrimitiveLM,
        characterGraphLM: CharacterGraphLM,
    ) {
        val votes = mutableListOf<CmpMessage>()
        while (true) {
            val vote = primitiveLM.sendOutVote()
            if (!vote.passMessage) break
            votes += vote
        }
        if (votes.isNotEmpty()) characterGraphLM.matchingStep(votes)
    }

    private fun evidenceOf(characterGraphLM: CharacterGraphLM): Map<String, Float> {
        val vote = characterGraphLM.sendOutVote()
        check(vote.passMessage) { "expected recognition evidence but got none" }
        return (vote.nonMorphologicalFeatures as RecognitionFeatures).evidenceByLabel
    }

    private fun lineShape(): List<RawPoint> = List(30) { i -> RawPoint(i.toFloat(), i.toFloat()) }

    private fun lShape(pointsPerLeg: Int = 20): List<RawPoint> {
        val step = 19f / (pointsPerLeg - 1)
        val down = List(pointsPerLeg) { i -> RawPoint(0f, i * step) }
        val right = List(pointsPerLeg) { i -> RawPoint(i * step, 19f) }
        return down + right.drop(1)
    }

    private fun arcShape(): List<RawPoint> =
        List(30) { i ->
            val angle = PI * i / 29
            RawPoint(cos(angle).toFloat(), sin(angle).toFloat())
        }

    @Test
    fun `a fresh instance of a taught shape scores its own label highest`() {
        val memory = GraphMemory()
        val primitiveLM = PrimitiveLM(lmId = "primitive-0")
        val characterGraphLM = CharacterGraphLM(lmId = "character-0", memory = memory)

        // Teach three structurally distinct shapes.
        drive(lineShape(), primitiveLM, characterGraphLM)
        characterGraphLM.teach("line")

        drive(lShape(), primitiveLM, characterGraphLM)
        characterGraphLM.teach("L")

        drive(arcShape(), primitiveLM, characterGraphLM)
        characterGraphLM.teach("arc")

        // A *fresh* instance of "L": different point density and a different scale.
        val freshL = lShape(pointsPerLeg = 35).map { it * 2f }
        drive(freshL, primitiveLM, characterGraphLM)
        val evidence = evidenceOf(characterGraphLM)

        assertTrue("expected all 3 labels scored: $evidence", evidence.keys.containsAll(setOf("line", "L", "arc")))
        assertEquals("L", evidence.maxByOrNull { it.value }?.key)
        assertTrue("expected a strong match but was ${evidence["L"]}", evidence.getValue("L") > 0.9f)
    }

    @Test
    fun `state captures taught labels and loadState restores them into a fresh instance`() {
        val memory = GraphMemory()
        val primitiveLM = PrimitiveLM(lmId = "primitive-0")
        val characterGraphLM = CharacterGraphLM(lmId = "character-0", memory = memory)

        drive(lineShape(), primitiveLM, characterGraphLM)
        characterGraphLM.teach("line")

        val restored = CharacterGraphLM(lmId = "character-1", memory = GraphMemory())
        restored.loadState(characterGraphLM.state())

        assertEquals(characterGraphLM.state().keys, restored.state().keys)
    }
}
