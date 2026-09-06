package com.eta.tbp.lib.sensor

import com.eta.tbp.lib.cmp.CmpMessage

/** Mirrors `abstract_monty_classes.SensorModule`. */
interface SensorModule<T> {
    val sensorId: String

    fun step(observation: T): CmpMessage

    fun preEpisode()

    fun postEpisode()
}
