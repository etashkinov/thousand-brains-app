package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.Location
import kotlin.math.sqrt

class FloatLocation(
    val location: FloatArray,
) : Location {
    override fun displacement(from: Location): Location {
        if (from !is FloatLocation) {
            return Location.Infinity
        }

        if (location.size != from.location.size) {
            throw IllegalArgumentException("Expected same dimensional locations of ${location.size}, given: ${from.location.size}")
        }

        return FloatLocation(location.mapIndexed { index, f -> f - from.location[index] }.toFloatArray())
    }

    override fun plus(displacement: Location): Location {
        if (displacement !is FloatLocation || location.size != displacement.location.size) return Location.Infinity
        return FloatLocation(location.mapIndexed { index, f -> f + displacement.location[index] }.toFloatArray())
    }

    override fun magnitude(): Float = sqrt(location.sumOf { (it * it).toDouble() }).toFloat()

    override fun mergedWith(
        other: Location,
        selfWeight: Float,
        totalWeight: Float,
    ): Location {
        if (other !is FloatLocation || location.size != other.location.size) return Location.Infinity
        return FloatLocation(location.mapIndexed { index, f -> (f * selfWeight + other.location[index]) / totalWeight }.toFloatArray())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FloatLocation) return false
        return location.contentEquals(other.location)
    }

    override fun hashCode(): Int = location.contentHashCode()

    override fun toString(): String = "FloatLocation(${location.joinToString()})"

    companion object {
        /** `FloatLocation(1f, 2f)` — a real secondary `vararg` constructor would clash with the FloatArray one at the JVM level (both erase to `([F)V`), so this is a companion factory instead. */
        operator fun invoke(vararg elements: Float): FloatLocation = FloatLocation(floatArrayOf(*elements))
    }
}
