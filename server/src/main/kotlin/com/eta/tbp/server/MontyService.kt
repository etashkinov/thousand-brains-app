package com.eta.tbp.server

import com.eta.tbp.lib.experiment.Experiment
import com.eta.tbp.lib.lm.ExperimentMode
import com.eta.tbp.lib.lm.HypothesisState
import com.eta.tbp.lib.log.Logger
import com.eta.tbp.lib.memory.Feature
import com.eta.tbp.lib.memory.GraphObjectModel
import com.eta.tbp.lib.memory.LabelFeature
import com.eta.tbp.lib.memory.Location
import com.eta.tbp.lib.sensor.FloatLocation
import com.eta.tbp.lib.sensor.GridEnvironment

data class LandmarkInput(
    val row: Int,
    val col: Int,
    val label: String,
)

/** [step] is this cell's 1-based position in the episode's visit order — see `Experiment.visitedLocationsInOrder`. [state] is `"NO_MATCH"`/`"TIED"`/`"CONFIRMED"` — see [encodeHypothesisState]. */
data class GridCell(
    val row: Int,
    val col: Int,
    val step: Int,
    val state: String,
)

/** [row]/[col] are null for a decision not about a specific cell (e.g. a goal proposal) — see `LmDecision.location`'s own doc. [state] is `"NO_MATCH"`/`"TIED"`/`"CONFIRMED"` — see [encodeHypothesisState]. */
data class DecisionEntry(
    val step: Int,
    val row: Int?,
    val col: Int?,
    val message: String,
    val state: String,
)

/** [HypothesisState] as the plain string the web UI matches against to pick a highlight color — kept a bare tag rather than [HypothesisState.Tied]/[HypothesisState.Confirmed]'s own label payload, since [GridCell]/[DecisionEntry].message already carry those labels in human-readable form. */
private fun encodeHypothesisState(state: HypothesisState): String =
    when (state) {
        is HypothesisState.NoMatch -> "NO_MATCH"
        is HypothesisState.Tied -> "TIED"
        is HypothesisState.Confirmed -> "CONFIRMED"
    }

/** [startRow]/[startCol] place the explorer at that cell instead of [Experiment]'s own default random start — both null (the web UI's "no selection made" state) falls back to random, matching [Experiment.train]/[Experiment.evaluate]'s own defaults. */
data class ExperimentRequest(
    val mode: ExperimentMode,
    val cityName: String,
    val citySize: Int,
    val landmarks: List<LandmarkInput>,
    val startRow: Int? = null,
    val startCol: Int? = null,
)

data class ExperimentResult(
    val outcome: String,
    val label: String?,
    val confidence: Float?,
    val locationsVisited: Int,
    val visitedCells: List<GridCell>,
    val decisions: List<DecisionEntry>,
)

/**
 * Runs a full, autonomous [Experiment] episode per request — [com.eta.tbp.lib.lm.Explorer]'s
 * goal-directed "keep visiting until recognized, or the whole city's
 * toured" loop, not a single probe (an earlier version of this class ran
 * one [com.eta.tbp.lib.lm.EvidenceGraphLM.matchingStep] per click; this
 * replaces that with the same train/evaluate loop [com.eta.tbp.lib.city]'s
 * own tests exercise).
 *
 * Memory has to survive *between* requests — the whole point of the memory
 * panel is showing what's accumulated across cities/clicks — but
 * [Experiment] deliberately builds its own fresh
 * [com.eta.tbp.lib.lm.EvidenceGraphLM] every time it's constructed (see its
 * own class doc: "lm owns and constructs its own GraphMemory internally...
 * moving previously taught objects into a fresh Experiment... goes through
 * state/loadState instead"). So rather than keeping one long-lived
 * [Experiment] (impossible — it's bound to one [GridEnvironment] for its
 * whole lifetime) or one long-lived LM (impossible to hand to [Experiment]
 * at all), this class keeps the *snapshot* and round-trips it through
 * exactly the [Experiment.loadState]/[Experiment.state] checkpoint idiom
 * its own doc describes for this exact situation.
 *
 * [positionTolerance] defaults to 0f, matching this app's own city-domain
 * tests ([com.eta.tbp.lib.city] fixtures) — a grid cell's coordinates are
 * exact integers here, not the jittered floats real touch input produces,
 * so there's nothing to be tolerant of.
 */
class MontyService(
    private val positionTolerance: Float = 0f,
    private val logger: Logger = Logger.Console,
) {
    private var memory: Map<String, List<GraphObjectModel>> = emptyMap()

    @Synchronized
    fun runExperiment(request: ExperimentRequest): ExperimentResult {
        val cells: Map<Location, Feature> =
            request.landmarks.associate { landmark ->
                FloatLocation(landmark.row.toFloat(), landmark.col.toFloat()) to LabelFeature(landmark.label)
            }
        val environment = GridEnvironment(size = request.citySize, cells = cells)
        val experiment =
            Experiment(
                environment = environment,
                lmId = "web-lm",
                positionTolerance = positionTolerance,
                logger = logger,
            )
        experiment.loadState(memory)

        val start =
            if (request.startRow != null && request.startCol != null) {
                FloatLocation(request.startRow.toFloat(), request.startCol.toFloat())
            } else {
                null
            }
        val outcome =
            when (request.mode) {
                ExperimentMode.TRAIN -> if (start != null) experiment.train(request.cityName, start) else experiment.train(request.cityName)
                ExperimentMode.EVALUATE -> if (start != null) experiment.evaluate(start) else experiment.evaluate()
            }

        memory = experiment.state()
        val visitedCells =
            experiment.visitedLocationsWithState().mapIndexedNotNull { index, (location, state) ->
                (location as? FloatLocation)?.let {
                    GridCell(
                        row = it.location[0].toInt(),
                        col = it.location[1].toInt(),
                        step = index + 1,
                        state = encodeHypothesisState(state),
                    )
                }
            }
        val decisions =
            experiment.decisionLog().map { decision ->
                val location = decision.location as? FloatLocation
                DecisionEntry(
                    step = decision.step,
                    row = location?.location?.getOrNull(0)?.toInt(),
                    col = location?.location?.getOrNull(1)?.toInt(),
                    message = decision.message,
                    state = encodeHypothesisState(decision.state),
                )
            }

        return when (outcome) {
            is Experiment.Outcome.Recognized ->
                ExperimentResult(
                    outcome = "Recognized",
                    label = outcome.label,
                    confidence = outcome.confidence,
                    locationsVisited = outcome.locationsVisited,
                    visitedCells = visitedCells,
                    decisions = decisions,
                )
            is Experiment.Outcome.NoMatch ->
                ExperimentResult(
                    outcome = "NoMatch",
                    label = null,
                    confidence = null,
                    locationsVisited = outcome.locationsVisited,
                    visitedCells = visitedCells,
                    decisions = decisions,
                )
            is Experiment.Outcome.Taught ->
                ExperimentResult(
                    outcome = "Taught",
                    label = outcome.label,
                    confidence = null,
                    locationsVisited = outcome.locationsVisited,
                    visitedCells = visitedCells,
                    decisions = decisions,
                )
        }
    }

    @Synchronized
    fun memorySnapshot(): Map<String, List<GraphObjectModel>> = memory

    @Synchronized
    fun reset() {
        memory = emptyMap()
    }
}
