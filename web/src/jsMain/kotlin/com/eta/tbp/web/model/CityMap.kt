package com.eta.tbp.web.model

import kotlinx.serialization.Serializable

/** A landmark placed at a discrete grid cell, mirroring the lib module's city domain: a named point of interest at a [row]/[col] position, the web editor's stand-in for lib's `LabelFeature` at a `MapLocation`. */
@Serializable
data class Landmark(
    val row: Int,
    val col: Int,
    val label: String,
)

/** A user-authored city: an NxN [size] grid with zero or more [landmarks]. Persisted as-is (see `MapStorage`). */
@Serializable
data class CityMap(
    val id: String,
    val name: String,
    val size: Int,
    val landmarks: List<Landmark> = emptyList(),
)

const val MIN_MAP_SIZE = 2
const val MAX_MAP_SIZE = 12
const val DEFAULT_MAP_SIZE = 5
