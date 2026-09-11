package com.eta.tbp.lib.memory

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

    /** Averages [candidate]'s nodes (aligned to [target]'s own order) into the stored model, via each node's own [Location.mergedWith]/[Feature.mergedWith]. */
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
                    location = storedNode.location.mergedWith(candidateNode.location, existingWeight, totalWeight),
                    feature = storedNode.feature.mergedWith(candidateNode.feature, existingWeight, totalWeight),
                )
            }

        return target.copy(
            nodes = mergedNodes,
            edges = edgeChainOf(mergedNodes),
            exemplarCount = target.exemplarCount + 1,
        )
    }

    companion object {
        const val MERGE_THRESHOLD = 0.75f
    }
}
