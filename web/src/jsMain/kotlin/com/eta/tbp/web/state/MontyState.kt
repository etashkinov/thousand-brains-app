package com.eta.tbp.web.state

import com.eta.tbp.web.model.CityMap
import com.eta.tbp.web.monty.ExperimentRequestDto
import com.eta.tbp.web.monty.ExperimentResultDto
import com.eta.tbp.web.monty.LandmarkDto
import com.eta.tbp.web.monty.MemorySnapshotDto
import com.eta.tbp.web.monty.fetchMemory
import com.eta.tbp.web.monty.postExperiment
import com.eta.tbp.web.monty.resetMemory

/**
 * Talks to `:server`'s Monty (the same [com.eta.tbp.lib.lm.EvidenceGraphLM]
 * `app` uses for its own drawing recognizer, driven here by a full
 * [com.eta.tbp.lib.experiment.Experiment] episode over a city's landmark
 * graph instead — see `MontyService`'s class doc on the server side). Kept
 * separate from [AppState]: this is network/loading/error state about the
 * *server's* memory, not the locally-persisted map data [AppState] owns.
 * [onChange] re-renders only the Monty panel, never the map editor.
 */
class MontyState(
    private val onChange: () -> Unit,
) {
    var memory: MemorySnapshotDto? = null
        private set
    var lastResult: ExperimentResultDto? = null
        private set

    /** The [CityMap.id] [lastResult] was computed for — the editor only highlights [ExperimentResultDto.visitedCells] while this still matches the open draft, so switching maps doesn't paint another city's path onto this one. */
    var lastResultMapId: String? = null
        private set
    var loading: Boolean = false
        private set
    var error: String? = null
        private set

    fun refreshMemory() {
        loading = true
        error = null
        onChange()
        fetchMemory(
            onResult = { snapshot ->
                memory = snapshot
                loading = false
                onChange()
            },
            onError = { message ->
                error = message
                loading = false
                onChange()
            },
        )
    }

    /** Runs a full train/evaluate [com.eta.tbp.lib.experiment.Experiment] episode over [map] — see `MontyService.runExperiment`'s doc. [mode] is `"EVALUATE"` or `"TRAIN"`. */
    fun runExperiment(
        mode: String,
        map: CityMap,
    ) {
        loading = true
        error = null
        onChange()
        val request =
            ExperimentRequestDto(
                mode = mode,
                cityName = map.name.trim().ifEmpty { "Untitled city" },
                citySize = map.size,
                landmarks = map.landmarks.map { LandmarkDto(it.row, it.col, it.label) },
            )
        postExperiment(
            request,
            onResult = { result ->
                lastResult = result
                lastResultMapId = map.id
                loading = false
                onChange()
                refreshMemory()
            },
            onError = { message ->
                error = message
                loading = false
                onChange()
            },
        )
    }

    fun reset() {
        loading = true
        error = null
        onChange()
        resetMemory(
            onDone = {
                memory = MemorySnapshotDto(objects = emptyList())
                lastResult = null
                lastResultMapId = null
                loading = false
                onChange()
            },
            onError = { message ->
                error = message
                loading = false
                onChange()
            },
        )
    }
}
