package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.Location

interface Environment {
    val size: Int

    fun randomLocation(): Location

    fun featureAt(location: Location): Feature?
}
