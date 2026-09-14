package com.eta.tbp.web.storage

import com.eta.tbp.web.model.CityMap
import kotlinx.browser.localStorage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

private const val STORAGE_KEY = "tbp.city.maps"

/** All persistence for [CityMap]s: a single JSON array under one `localStorage` key. */
object MapStorage {
    fun loadAll(): List<CityMap> =
        localStorage.getItem(STORAGE_KEY)?.let { json ->
            runCatching { Json.decodeFromString<List<CityMap>>(json) }.getOrDefault(emptyList())
        } ?: emptyList()

    fun saveAll(maps: List<CityMap>) {
        localStorage.setItem(STORAGE_KEY, Json.encodeToString(maps))
    }

    fun newId(): String = Random.nextBytes(9).joinToString("") { ((it.toInt() and 0xFF) or 0x100).toString(16).substring(1) }
}
