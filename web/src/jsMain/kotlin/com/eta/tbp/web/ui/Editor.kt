package com.eta.tbp.web.ui

import com.eta.tbp.web.model.MAX_MAP_SIZE
import com.eta.tbp.web.model.MIN_MAP_SIZE
import com.eta.tbp.web.state.AppState
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.dom.append
import kotlinx.html.h2
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import kotlinx.html.label
import kotlinx.html.option
import kotlinx.html.p
import kotlinx.html.select
import kotlinx.html.textInput
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

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
                        attributes["id"] = "city-name"
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
                        attributes["id"] = "grid-size"
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
                    div(classes = "grid") {
                        attributes["style"] = "grid-template-columns: repeat(${draft.size}, 1fr);"
                        val byPosition = draft.landmarks.associateBy { it.row to it.col }
                        for (row in 0 until draft.size) {
                            for (col in 0 until draft.size) {
                                textInput(classes = "cell-input") {
                                    placeholder = "$row,$col"
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
