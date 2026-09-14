package com.eta.tbp.web.ui

import com.eta.tbp.web.model.MAX_MAP_SIZE
import com.eta.tbp.web.model.MIN_MAP_SIZE
import com.eta.tbp.web.state.AppState
import kotlinx.html.button
import kotlinx.html.dataList
import kotlinx.html.div
import kotlinx.html.dom.append
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.span
import kotlinx.html.textInput
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

private const val LANDMARK_LABELS_LIST_ID = "landmark-labels"

/** The right-hand panel: the create/edit form for [state]'s current draft, or an empty-state placeholder when nothing is being edited. */
fun renderEditor(
    container: HTMLElement,
    state: AppState,
) {
    container.innerHTML = ""
    val draft = state.draft
    container.append {
        if (draft == null) {
            div(classes = "empty-state") {
                p { +"Select a map on the left to edit it, or create a new one." }
            }
        } else {
            div(classes = "editor") {
                h2 { +(if (state.editingExistingId == null) "New city" else "Edit city") }

                div(classes = "field") {
                    label {
                        htmlFor = "city-name"
                        +"City name"
                    }
                    textInput(classes = "name-input") {
                        id = "city-name"
                        placeholder = "e.g. Springfield"
                        value = draft.name
                        onInputFunction = { event ->
                            state.renameDraftQuietly((event.target as HTMLInputElement).value)
                        }
                    }
                }

                div(classes = "field") {
                    label {
                        htmlFor = "grid-size"
                        +"Grid size"
                    }
                    select(classes = "size-select") {
                        id = "grid-size"
                        onChangeFunction = { event ->
                            state.resizeDraft((event.target as HTMLSelectElement).value.toInt())
                        }
                        for (size in MIN_MAP_SIZE..MAX_MAP_SIZE) {
                            option {
                                value = size.toString()
                                selected = size == draft.size
                                +"$size × $size"
                            }
                        }
                    }
                }

                div(classes = "field") {
                    label { +"Landmarks" }
                    dataList {
                        id = LANDMARK_LABELS_LIST_ID
                        for (knownLabel in knownLabels(state)) {
                            option { value = knownLabel }
                        }
                    }
                    div(classes = "grid") {
                        attributes["style"] = "grid-template-columns: auto repeat(${draft.size}, 1fr);"
                        span(classes = "grid-corner") {}
                        for (col in 0 until draft.size) {
                            span(classes = "grid-header") { +"$col" }
                        }
                        val byPosition = draft.landmarks.associateBy { it.row to it.col }
                        for (row in 0 until draft.size) {
                            span(classes = "grid-header") { +"$row" }
                            for (col in 0 until draft.size) {
                                textInput(classes = "cell-input") {
                                    list = LANDMARK_LABELS_LIST_ID
                                    value = byPosition[row to col]?.label ?: ""
                                    onInputFunction = { event ->
                                        state.setLandmarkQuietly(row, col, (event.target as HTMLInputElement).value)
                                    }
                                }
                            }
                        }
                    }
                }

                div(classes = "actions") {
                    button(classes = "save-button") {
                        onClickFunction = { state.saveDraft() }
                        +"Save map"
                    }
                    button(classes = "cancel-button") {
                        onClickFunction = { state.cancelEdit() }
                        +"Cancel"
                    }
                }
            }
        }
    }
}

/** Distinct landmark labels already used in any saved map or the current draft, for the "add a new one or pick an existing one" autocomplete on each landmark cell. */
private fun knownLabels(state: AppState): List<String> {
    val saved = state.maps.flatMap { it.landmarks }.map { it.label }
    val draft = state.draft?.landmarks?.map { it.label } ?: emptyList()
    return (saved + draft).distinct().sorted()
}
