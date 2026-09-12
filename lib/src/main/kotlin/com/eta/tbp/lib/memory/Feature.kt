package com.eta.tbp.lib.memory

/**
 * What [GraphMatcher]/[GraphMemory] need to know about a node's feature payload to
 * compare and merge two nodes — expressed as methods [F] implements (same idiom as
 * `Comparable<T>`) rather than injected config, so [GraphMatcher]/[GraphMemory]/
 * [com.eta.tbp.lib.lm.EvidenceGraphLM] stay parameter-free generic machinery, zero
 * F-specific construction at any call site. [difference] mirrors real Monty's
 * per-feature evidence calculation (a single normalized difference, 0 = identical)
 * rather than a separate boolean-gate + continuous-score pair; a feature kind that
 * should act as a hard veto (like a taught label) returns [Float.POSITIVE_INFINITY]
 * for a mismatch instead of needing its own boolean.
 *
 * [difference]/[mergedWith] default to pure-[label] identity (0 if [label]s match,
 * else infinite; merging is a no-op) — [LabelFeature] is exactly this default with
 * no fields of its own. A feature carrying real measurement beyond its label (e.g.
 * [com.eta.tbp.lib.sensor.PrimitiveFeature]'s `extent`) overrides both instead of
 * inheriting them, the same way it would if these methods were abstract; the
 * default only saves that override for feature kinds that have nothing to add.
 */
interface Feature {
    val label: String

    /** 0 = identical; [Float.POSITIVE_INFINITY] if [other] should never be treated as a match. */
    fun difference(other: Feature): Float = if (other.label == label) 0f else Float.POSITIVE_INFINITY

    /** Weighted merge of this feature (weight [selfWeight]) with [other], normalized by [totalWeight]. */
    fun mergedWith(
        other: Feature,
        selfWeight: Float,
        totalWeight: Float,
    ): Feature = this

    object Infinity : Feature {
        override val label = "INFINITY"

        override fun difference(other: Feature) = Float.POSITIVE_INFINITY

        override fun mergedWith(
            other: Feature,
            selfWeight: Float,
            totalWeight: Float,
        ) = this
    }
}
