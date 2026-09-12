package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.cmp.CmpMessage
import com.eta.tbp.lib.memory.Location

/** Mirrors `abstract_monty_classes.SensorModule`. */
interface SensorModule {
    val sensorId: String

    fun step(observation: Location): CmpMessage

    fun preEpisode()

    fun postEpisode()
}
