package com.eta.tbp.lib.memory

/**
 * The trivial [Feature]: identity is exactly [label], nothing else — so
 * [difference]/[mergedWith] are left to [Feature]'s own defaults rather than
 * restated here. Used wherever a domain's identity doesn't carry a
 * continuous measurement alongside it (e.g. a city grid cell's point of
 * interest — a post office, park, or bakery — [com.eta.tbp.lib.sensor.GridEnvironment]
 * senses from); [com.eta.tbp.lib.sensor.PrimitiveFeature] is the contrasting
 * case, overriding both because a stroke primitive's `extent` is real
 * measurement [difference] must account for beyond just the label.
 */
data class LabelFeature(
    override val label: String,
) : Feature
