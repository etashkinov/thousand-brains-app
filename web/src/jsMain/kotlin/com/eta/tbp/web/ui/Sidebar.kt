package com.eta.tbp.web.ui

import com.eta.tbp.web.model.CityMap
import com.eta.tbp.web.state.AppState
import kotlinx.browser.window
import kotlinx.html.button
import kotlinx.html.dom.append
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.js.onClickFunction
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul
import org.w3c.dom.HTMLElement

/** The left-hand nav: create/select/delete a [CityMap][com.eta.tbp.web.model.CityMap]. */
fun renderSidebar(
    container: HTMLElement,
    state: AppState,
) {
    container.innerHTML = ""
    container.append {
        h1 { +"City Maps" }
        button(classes = "new-map-button") {
            onClickFunction = { state.startNew() }
            +"+ New map"
        }
        if (state.maps.isEmpty()) {
            p(classes = "empty-hint") { +"No maps yet — create one to get started." }
        } else {
            ul(classes = "map-list") {
                for (map in state.maps) {
                    li(classes = if (map.id == state.editingExistingId) "map-item selected" else "map-item") {
                        span(classes = "map-name") {
                            onClickFunction = { state.edit(map.id) }
                            +"${map.name} (${map.size}×${map.size})"
                        }
                        button(classes = "delete-button") {
                            attributes["aria-label"] = "Delete ${map.name}"
                            onClickFunction = { event ->
                                event.stopPropagation()
                                if (window.confirm("Delete \"${map.name}\"?")) {
                                    state.delete(map.id)
                                }
                            }
                            +"×"
                        }
                    }
                }
            }
        }

        h2(classes = "sidebar-section-title") { +"Labels" }
        val citiesByLabel = citiesByLabel(state.maps)
        if (citiesByLabel.isEmpty()) {
            p(classes = "empty-hint") { +"No landmarks yet." }
        } else {
            ul(classes = "label-list") {
                for ((label, cities) in citiesByLabel) {
                    li(classes = "label-item") {
                        span(classes = "label-name") { +label }
                        span(classes = "label-cities") { +cities.joinToString(", ") }
                    }
                }
            }
        }
    }
}

/** Every distinct landmark label across [maps], sorted, with the (sorted, deduped) names of the cities it appears in. */
private fun citiesByLabel(maps: List<CityMap>): Map<String, List<String>> =
    maps
        .flatMap { map -> map.landmarks.map { it.label to map.name } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, cities) -> cities.distinct().sorted() }
        .toList()
        .sortedBy { (label, _) -> label }
        .toMap()
