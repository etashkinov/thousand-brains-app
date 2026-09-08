package com.eta.tbp.lib.util

/**
 * The order- and direction-tolerant brute-force alignment search
 * originally built for [com.eta.tbp.lib.memory.GraphMatcher] (whole
 * character graphs, a handful of sparse primitive nodes), extracted so
 * [com.eta.tbp.lib.lm.PrimitiveGraphLM] (short, dense per-window point
 * sequences) can reuse the same search over its own, differently-shaped
 * node type — without forcing both tiers to share one node schema. Deciding
 * *how* two nodes score against each other is still each caller's own
 * business (see [score]); this only owns *which offset and direction*
 * search finds the best-scoring alignment.
 *
 * Tries every rotation (start offset) and both directions of [stored]
 * against [target], since the user may start drawing from a different
 * point than they did when teaching, or trace the same shape in reverse,
 * and neither should count as a different shape.
 */
object AlignmentSearch {
    private val DIRECTIONS = intArrayOf(1, -1)

    /** A window of [stored] paired with how well it scores against [target] at some offset/direction. */
    data class Result<T>(
        val window: List<T>,
        val score: Float,
    )

    /**
     * @param stored the full stored sequence to search over.
     * @param target the (possibly shorter, for a live partial match)
     *   sequence to align against — [target].size fixes the window length.
     * @param score how well one candidate `stored` window matches [target],
     *   in whatever units the caller likes; higher is better. Called once
     *   per offset/direction combination tried.
     * @return the best-scoring window and its score; `score` 0f (via an
     *   empty window) if [stored] is empty.
     */
    fun <T> bestAlignment(
        stored: List<T>,
        target: List<T>,
        score: (window: List<T>, target: List<T>) -> Float,
    ): Result<T> {
        var best = Result<T>(window = emptyList(), score = 0f)
        if (stored.isEmpty()) return best

        for (direction in DIRECTIONS) {
            for (offset in stored.indices) {
                val window = windowOf(stored, offset, direction, target.size)
                val candidateScore = score(window, target)
                if (candidateScore > best.score) best = Result(window, candidateScore)
            }
        }
        return best
    }

    private fun <T> windowOf(
        nodes: List<T>,
        offset: Int,
        direction: Int,
        length: Int,
    ): List<T> {
        val n = nodes.size
        return List(length) { i -> nodes[Math.floorMod(offset + i * direction, n)] }
    }
}
