package com.eta.tbp.web.ui

import com.eta.tbp.web.state.AppState
import com.eta.tbp.web.state.MontyState
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.dom.append
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.js.onClickFunction
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul
import org.w3c.dom.HTMLElement

/**
 * The right-hand panel: what Monty (`:server`'s
 * [com.eta.tbp.lib.experiment.Experiment], over HTTP — see
 * [com.eta.tbp.web.monty.MontyClient]) has learned so far, plus the
 * evaluate/train controls that run a full autonomous episode over the
 * currently open map.
 */
fun renderMontyPanel(
    container: HTMLElement,
    montyState: MontyState,
    appState: AppState,
) {
    container.innerHTML = ""
    val draft = appState.draft
    val lastResult = montyState.lastResult
    val memory = montyState.memory

    container.append {
        h1 { +"Monty" }

        montyState.error?.let { message ->
            p(classes = "monty-error") { +message }
        }

        if (draft == null) {
            p(classes = "empty-hint") { +"Open a map to evaluate or train Monty on it." }
        } else {
            val cityName = draft.name.trim().ifEmpty { "Untitled city" }

            p(classes = "experiment-hint") { +"Explores $cityName's whole grid, in random order, until recognized." }
            div(classes = "experiment-actions") {
                button(classes = "evaluate-button") {
                    disabled = montyState.loading
                    onClickFunction = { montyState.runExperiment("EVALUATE", draft) }
                    +"Evaluate"
                }
                button(classes = "train-button") {
                    disabled = montyState.loading
                    onClickFunction = { montyState.runExperiment("TRAIN", draft) }
                    +"Train as \"$cityName\""
                }
            }

            if (lastResult != null) {
                div(classes = "experiment-result") {
                    p {
                        +when (lastResult.outcome) {
                            "Recognized" -> "Recognized: ${lastResult.label} (${formatConfidence(lastResult.confidence)})"
                            "Taught" -> "Taught \"${lastResult.label}\" as a new city."
                            else -> "No match — nothing taught explains this city."
                        }
                    }
                    p { +"Visited ${lastResult.locationsVisited} location${if (lastResult.locationsVisited == 1) "" else "s"}." }
                }
            }
        }

        h2(classes = "sidebar-section-title") { +"Stored memory" }
        button(classes = "reset-button") {
            onClickFunction = { montyState.reset() }
            +"Reset memory"
        }

        val objects = memory?.objects
        if (objects == null) {
            p(classes = "empty-hint") { +"Loading…" }
        } else if (objects.isEmpty()) {
            p(classes = "empty-hint") { +"Nothing taught yet." }
        } else {
            ul(classes = "memory-list") {
                for (obj in objects.sortedBy { it.label }) {
                    li(classes = "memory-item") {
                        val exemplarWord = if (obj.exemplars.size == 1) "exemplar" else "exemplars"
                        span(classes = "memory-label") { +"${obj.label} (${obj.exemplars.size} $exemplarWord)" }
                        for (exemplar in obj.exemplars) {
                            span(classes = "memory-exemplar") { +exemplar.nodes.joinToString(", ") { it.label } }
                        }
                    }
                }
            }
        }
    }
}

private fun formatConfidence(value: Float?): String {
    if (value == null) return "?"
    return "${(value * 100).toInt()}%"
}
