package com.eta.tbp.web.state

import com.eta.tbp.web.model.CityMap
import com.eta.tbp.web.model.DEFAULT_MAP_SIZE
import com.eta.tbp.web.model.Landmark
import com.eta.tbp.web.storage.MapStorage

/**
 * The app's whole in-memory state: the saved [maps] plus at most one [draft] being
 * created or edited. [onChange] re-renders the page; structural edits (new/open/resize/
 * save/delete/cancel) call it, but per-keystroke edits (name, landmark labels) go through
 * [updateDraftQuietly] and skip it — rebuilding the DOM on every keystroke would steal
 * focus from the input the user is typing into.
 */
class AppState(
    private val onChange: () -> Unit,
) {
    var maps: List<CityMap> = MapStorage.loadAll()
        private set
    var draft: CityMap? = null
        private set

    /** The id of the saved map [draft] was opened from, or `null` when [draft] is a new, not-yet-saved map. */
    var editingExistingId: String? = null
        private set

    /**
     * Where to place the explorer for the next [MontyState.runExperiment] run on [draft] —
     * `null` (the default) leaves it to `Experiment`'s own random start. Not part of
     * [CityMap] itself: it's a per-run choice for whoever is at the keyboard right now, not
     * a property of the map worth persisting to [MapStorage], so it resets with [draft]
     * itself on every [startNew]/[edit]/[cancelEdit].
     */
    var startLocation: Pair<Int, Int>? = null
        private set

    fun startNew() {
        draft = CityMap(id = MapStorage.newId(), name = "", size = DEFAULT_MAP_SIZE)
        editingExistingId = null
        startLocation = null
        onChange()
    }

    fun edit(id: String) {
        val map = maps.find { it.id == id } ?: return
        draft = map
        editingExistingId = id
        startLocation = null
        onChange()
    }

    fun cancelEdit() {
        draft = null
        editingExistingId = null
        startLocation = null
        onChange()
    }

    fun resizeDraft(size: Int) {
        val current = draft ?: return
        draft =
            current.copy(
                size = size,
                landmarks = current.landmarks.filter { it.row < size && it.col < size },
            )
        startLocation = startLocation?.takeIf { (row, col) -> row < size && col < size }
        onChange()
    }

    /** Toggles [row]/[col] as [startLocation] — clicking the already-selected start cell clears the pick (back to a random start) rather than requiring a separate "clear" control. */
    fun setStartLocation(
        row: Int,
        col: Int,
    ) {
        startLocation = (row to col).takeUnless { it == startLocation }
        onChange()
    }

    /** Mutates [draft] without re-rendering. See the class doc for why. */
    fun updateDraftQuietly(transform: (CityMap) -> CityMap) {
        draft = draft?.let(transform)
    }

    fun renameDraftQuietly(name: String) {
        updateDraftQuietly { it.copy(name = name) }
    }

    fun setLandmarkQuietly(
        row: Int,
        col: Int,
        label: String,
    ) {
        updateDraftQuietly { map ->
            val withoutCell = map.landmarks.filterNot { it.row == row && it.col == col }
            val trimmed = label.trim()
            map.copy(landmarks = if (trimmed.isEmpty()) withoutCell else withoutCell + Landmark(row, col, trimmed))
        }
    }

    fun saveDraft() {
        val saved = draft?.let { it.copy(name = it.name.trim().ifEmpty { "Untitled city" }) } ?: return
        maps =
            if (maps.any { it.id == saved.id }) {
                maps.map { if (it.id == saved.id) saved else it }
            } else {
                maps + saved
            }
        MapStorage.saveAll(maps)
        draft = null
        editingExistingId = null
        onChange()
    }

    fun delete(id: String) {
        maps = maps.filterNot { it.id == id }
        MapStorage.saveAll(maps)
        if (editingExistingId == id) {
            draft = null
            editingExistingId = null
        }
        onChange()
    }
}
