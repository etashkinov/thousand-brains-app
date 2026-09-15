package com.eta.tbp.web.monty

import kotlinx.serialization.Serializable

/** The wire shapes for `:server`'s `/experiment` and `/memory` endpoints — see `server/src/main/kotlin/com/eta/tbp/server/Main.kt`'s `encode*`/`decode*` functions for the JSON this must match. */
@Serializable
data class LandmarkDto(
    val row: Int,
    val col: Int,
    val label: String,
)

/** [startRow]/[startCol] are both null when the user hasn't picked a start cell — the server then falls back to a random start, matching `Experiment.train`/`.evaluate`'s own defaults. */
@Serializable
data class ExperimentRequestDto(
    val mode: String,
    val cityName: String,
    val citySize: Int,
    val landmarks: List<LandmarkDto>,
    val startRow: Int? = null,
    val startCol: Int? = null,
)

/** [step] is this cell's 1-based position in the episode's visit order. [state] is `"NO_MATCH"`/`"TIED"`/`"CONFIRMED"` — mirrors `HypothesisState`, reduced server-side to a bare tag (see `MontyService.encodeHypothesisState`'s own doc for why); [hypothesisStateClass] turns it into this UI's own CSS hook. */
@Serializable
data class GridCellDto(
    val row: Int,
    val col: Int,
    val step: Int,
    val state: String,
)

/** [row]/[col] are null for a decision not about a specific cell (e.g. a goal proposal naming somewhere not yet visited) — mirrors `LmDecision.location`'s own doc. [state] — see [GridCellDto.state]'s own doc. */
@Serializable
data class DecisionEntryDto(
    val step: Int,
    val row: Int? = null,
    val col: Int? = null,
    val message: String,
    val state: String,
)

/** [GridCellDto.state]/[DecisionEntryDto.state] turned into this UI's own CSS class name — one hook shared by the grid's cell highlighting and the exploration log's text color, so the two can never drift into using different color rules for the same state. Falls back to the neutral "no-match" look for any unrecognized tag rather than crashing a render — this DTO crosses an HTTP boundary, so a mismatched client/server pair is a real (if unlikely) possibility, not just a type-system hypothetical. */
fun hypothesisStateClass(state: String): String =
    when (state) {
        "CONFIRMED" -> "state-confirmed"
        "TIED" -> "state-tied"
        else -> "state-no-match"
    }

/** [outcome] is `"Recognized"`, `"NoMatch"` (evaluate only), or `"Taught"` (train only) — mirrors `Experiment.Outcome`'s three cases. [visitedCells] is every cell that episode visited, in visit order — for highlighting (and numbering) the path Monty actually took. [decisions] is the LM's own step-by-step reasoning for that same episode, in order — see `EvidenceGraphLM.decisionLog`'s own doc. */
@Serializable
data class ExperimentResultDto(
    val outcome: String,
    val label: String? = null,
    val confidence: Float? = null,
    val locationsVisited: Int,
    val visitedCells: List<GridCellDto> = emptyList(),
    val decisions: List<DecisionEntryDto> = emptyList(),
)

@Serializable
data class MemoryNodeDto(
    val row: Int,
    val col: Int,
    val label: String,
)

@Serializable
data class MemoryExemplarDto(
    val nodes: List<MemoryNodeDto>,
)

@Serializable
data class MemoryObjectDto(
    val label: String,
    val exemplars: List<MemoryExemplarDto>,
)

@Serializable
data class MemorySnapshotDto(
    val objects: List<MemoryObjectDto>,
)
