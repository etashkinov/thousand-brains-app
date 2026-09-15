package com.eta.tbp.web.ui

import com.eta.tbp.web.monty.hypothesisStateClass
import com.eta.tbp.web.state.AppState
import com.eta.tbp.web.state.MontyState
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.dom.append
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.js.onClickFunction
import kotlinx.html.li
import kotlinx.html.ol
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.ul
import org.w3c.dom.HTMLElement

/**
 * The right-hand panel: what Monty (`:server`'s
 * [com.eta.tbp.lib.experiment.Experiment], over HTTP — see
 * [com.eta.tbp.web.monty.MontyClient]) has learned so far, plus the
 * evaluate/train controls that run a full autonomous episode over the
 * currently open map. When the last run produced any
 * [com.eta.tbp.web.monty.DecisionEntryDto]s, they're listed under
 * "Exploration log" in the order `EvidenceGraphLM` made them — the same
 * per-step reasoning ([com.eta.tbp.lib.lm.EvidenceGraphLM.decisionLog]'s own
 * doc) a console would otherwise only get via [com.eta.tbp.lib.log.Logger]
 * debug output.
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
            val start = appState.startLocation

            p(classes = "experiment-hint") {
                +if (start == null) {
                    "Explores $cityName's whole grid, from a random start, until recognized."
                } else {
                    "Explores $cityName's whole grid, starting at (${start.first}, ${start.second}), until recognized."
                }
            }
            div(classes = "experiment-actions") {
                button(classes = "evaluate-button") {
                    disabled = montyState.loading
                    onClickFunction = { montyState.runExperiment("EVALUATE", draft, start) }
                    +"Evaluate"
                }
                button(classes = "train-button") {
                    disabled = montyState.loading
                    onClickFunction = { montyState.runExperiment("TRAIN", draft, start) }
                    +"Train as \"$cityName\""
                }
            }

            if (lastResult != null) {
                div(classes = "experiment-result") {
                    p(classes = "outcome-text ${outcomeStateClass(lastResult.outcome)}") {
                        +when (lastResult.outcome) {
                            "Recognized" -> "Recognized: ${lastResult.label} (${formatConfidence(lastResult.confidence)})"
                            "Taught" -> "Taught \"${lastResult.label}\" as a new city."
                            else -> "No match — nothing taught explains this city."
                        }
                    }
                    p { +"Visited ${lastResult.locationsVisited} location${if (lastResult.locationsVisited == 1) "" else "s"}." }
                }
            }

            if (lastResult != null && lastResult.decisions.isNotEmpty()) {
                h2(classes = "sidebar-section-title") { +"Exploration log" }
                ol(classes = "decision-log") {
                    for (decision in lastResult.decisions) {
                        li(classes = "decision-item") {
                            if (decision.row != null && decision.col != null) {
                                span(classes = "decision-location") { +"(${decision.row}, ${decision.col})" }
                            }
                            span(classes = "decision-message ${hypothesisStateClass(decision.state)}") { +decision.message }
                        }
                    }
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

/** [com.eta.tbp.web.monty.ExperimentResultDto.outcome] read as the same [hypothesisStateClass] hook the grid/exploration log use — "Recognized"/"Taught" are both a successful landing on one label ([com.eta.tbp.lib.lm.HypothesisState.Confirmed]'s own visual), "NoMatch" the same look as a discarded/empty hypothesis. */
private fun outcomeStateClass(outcome: String): String =
    when (outcome) {
        "Recognized", "Taught" -> "state-confirmed"
        else -> "state-no-match"
    }
