package com.eta.tbp.lib.memory

import com.eta.tbp.lib.sensor.PrimitiveMeasurement
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * `Map<label, variants>` — multiple [GraphObjectModel]s can share a label
 * (natural handwriting variation: different stroke order/count for the
 * "same" character shouldn't force-merge into one corrupted model).
 */
class GraphMemory {
    private val models = mutableMapOf<String, MutableList<GraphObjectModel>>()

    /** Mirrors Monty's `detect_new_object_k_steps` — the merge-vs-spawn decision. */
    fun detectNewObject(
        candidate: GraphObjectModel,
        label: String,
    ): Boolean {
        val existing = models[label] ?: return true
        val bestScore = existing.maxOfOrNull { GraphMatcher.matchScore(it, candidate) } ?: 0f
        return bestScore < MERGE_THRESHOLD
    }

    fun addOrMerge(
        candidate: GraphObjectModel,
        label: String,
    ) {
        val variants = models.getOrPut(label) { mutableListOf() }
        val bestIndex = variants.indices.maxByOrNull { GraphMatcher.matchScore(variants[it], candidate) }
        val bestScore = bestIndex?.let { GraphMatcher.matchScore(variants[it], candidate) } ?: 0f

        if (bestIndex == null || bestScore < MERGE_THRESHOLD) {
            variants.add(candidate)
        } else {
            variants[bestIndex] = mergeInto(variants[bestIndex], candidate)
        }
    }

    fun candidatesForLabel(label: String): List<GraphObjectModel> = models[label] ?: emptyList()

    fun allLabels(): Set<String> = models.keys

    fun snapshot(): Map<String, List<GraphObjectModel>> = models.mapValues { it.value.toList() }

    fun restore(snapshot: Map<String, List<GraphObjectModel>>) {
        models.clear()
        snapshot.forEach { (label, variants) -> models[label] = variants.toMutableList() }
    }

    /** Averages [candidate]'s nodes (aligned to [target]'s own order) into the stored model. */
    private fun mergeInto(
        target: GraphObjectModel,
        candidate: GraphObjectModel,
    ): GraphObjectModel {
        val alignedWindow = GraphMatcher.bestAlignedWindow(target, candidate) ?: return target
        val existingWeight = target.exemplarCount.toFloat()
        val totalWeight = existingWeight + 1f

        val mergedNodes =
            target.nodes.mapIndexed { i, storedNode ->
                val candidateNode = alignedWindow[i]
                storedNode.copy(
                    location =
                        weightedAverage(storedNode.location, existingWeight, candidateNode.location, totalWeight),
                    absoluteAngle =
                        weightedAverageAngle(
                            storedNode.absoluteAngle,
                            existingWeight,
                            candidateNode.absoluteAngle,
                            totalWeight,
                        ),
                    measurement =
                        weightedAverageMeasurement(
                            storedNode.measurement,
                            existingWeight,
                            candidateNode.measurement,
                            totalWeight,
                        ),
                )
            }

        return target.copy(
            nodes = mergedNodes,
            edges = edgeChainOf(mergedNodes),
            exemplarCount = target.exemplarCount + 1,
        )
    }

    private fun weightedAverage(
        stored: FloatArray,
        storedWeight: Float,
        candidate: FloatArray,
        totalWeight: Float,
    ): FloatArray =
        floatArrayOf(
            (stored[0] * storedWeight + candidate[0]) / totalWeight,
            (stored[1] * storedWeight + candidate[1]) / totalWeight,
        )

    /**
     * Plain weighted average, per variant. Unlike [weightedAverageAngle],
     * neither [PrimitiveMeasurement] field is a periodic heading that needs
     * wraparound-aware averaging: a line's length is already a plain
     * magnitude, and an arc's sweep angle is a total accumulated rotation
     * (see [com.eta.tbp.lib.sensor.PrimitiveSensorModule.sweepAngleOf]), not
     * a direction mod 2*PI, and a radius is a plain magnitude too. [stored]
     * and [candidate] are always the same variant here —
     * [GraphMatcher.bestAlignedWindow] only aligns nodes whose
     * [GraphNode.measurement] variant already matched.
     */
    private fun weightedAverageMeasurement(
        stored: PrimitiveMeasurement,
        storedWeight: Float,
        candidate: PrimitiveMeasurement,
        totalWeight: Float,
    ): PrimitiveMeasurement =
        when (stored) {
            is PrimitiveMeasurement.Line -> {
                candidate as PrimitiveMeasurement.Line
                PrimitiveMeasurement.Line((stored.length * storedWeight + candidate.length) / totalWeight)
            }
            is PrimitiveMeasurement.Arc -> {
                candidate as PrimitiveMeasurement.Arc
                PrimitiveMeasurement.Arc(
                    sweepAngle = (stored.sweepAngle * storedWeight + candidate.sweepAngle) / totalWeight,
                    radius = (stored.radius * storedWeight + candidate.radius) / totalWeight,
                )
            }
        }

    /** Circular weighted mean via the sin/cos trick, so averaging never breaks at the +-PI wraparound. */
    private fun weightedAverageAngle(
        stored: Float,
        storedWeight: Float,
        candidate: Float,
        totalWeight: Float,
    ): Float {
        val x = (cos(stored) * storedWeight + cos(candidate)) / totalWeight
        val y = (sin(stored) * storedWeight + sin(candidate)) / totalWeight
        return atan2(y, x)
    }

    companion object {
        const val MERGE_THRESHOLD = 0.75f
    }
}
