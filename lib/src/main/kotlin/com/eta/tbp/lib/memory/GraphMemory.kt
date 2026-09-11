package com.eta.tbp.lib.memory

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * `Map<label, variants>` — multiple [GraphObjectModel]s can share a label
 * (natural handwriting variation: different stroke order/count for the
 * "same" character shouldn't force-merge into one corrupted model).
 */
class GraphMemory<F : EvidenceFeature<F>> {
    private val models = mutableMapOf<String, MutableList<GraphObjectModel<F>>>()

    /** Mirrors Monty's `detect_new_object_k_steps` — the merge-vs-spawn decision. */
    fun detectNewObject(
        candidate: GraphObjectModel<F>,
        label: String,
    ): Boolean {
        val existing = models[label] ?: return true
        val bestScore = existing.maxOfOrNull { GraphMatcher.matchScore(it, candidate) } ?: 0f
        return bestScore < MERGE_THRESHOLD
    }

    fun addOrMerge(
        candidate: GraphObjectModel<F>,
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

    fun candidatesForLabel(label: String): List<GraphObjectModel<F>> = models[label] ?: emptyList()

    fun allLabels(): Set<String> = models.keys

    fun snapshot(): Map<String, List<GraphObjectModel<F>>> = models.mapValues { it.value.toList() }

    fun restore(snapshot: Map<String, List<GraphObjectModel<F>>>) {
        models.clear()
        snapshot.forEach { (label, variants) -> models[label] = variants.toMutableList() }
    }

    /** Averages [candidate]'s nodes (aligned to [target]'s own order) into the stored model. */
    private fun mergeInto(
        target: GraphObjectModel<F>,
        candidate: GraphObjectModel<F>,
    ): GraphObjectModel<F> {
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
                    feature = storedNode.feature.mergedWith(candidateNode.feature, existingWeight, totalWeight),
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
