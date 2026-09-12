package com.eta.tbp.lib.city

import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.FloatLocation.Companion.invoke
import kotlin.random.Random

fun springfield(
    origin: Location,
    random: Random = Random.Default,
) = CityMap.of(
    size = 10,
    origin to "post_office",
    origin.plus(FloatLocation(2f, 1f)) to "park",
    origin.plus(FloatLocation(1f, 3f)) to "bakery",
    random = random,
)

fun shelbyville(
    origin: Location,
    random: Random = Random.Default,
) = CityMap.of(
    size = 10,
    origin to "post_office",
    origin.plus(FloatLocation(2f, 1f)) to "park",
    origin.plus(FloatLocation(1f, -3f)) to "bakery",
    random = random,
)

fun capitalCity(
    origin: Location,
    random: Random = Random.Default,
) = CityMap.of(
    size = 10,
    origin to "school",
    origin.plus(FloatLocation(3f, 1f)) to "hospital",
    origin.plus(FloatLocation(1f, 2f)) to "cafe",
    random = random,
)
