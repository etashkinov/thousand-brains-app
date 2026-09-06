# Implementation Plan — TBP-Inspired Handwriting Recognition App

**Platform:** Android (Kotlin) · **Status:** Phases 0–3 done (`lib`'s brain
pipeline — sensor, primitives, character graph memory — is built and
unit-tested end to end); Phase 4 (UI wiring) is next.
**Core idea:** A two-tier, Thousand-Brains-Project-inspired recognizer that learns
handwritten characters live from touchscreen strokes, with no pretraining —
architecture ported as faithfully as possible from Monty's actual CMP message
format and `LearningModule`/`SensorModule` interfaces.

---

## 1. Design Summary

| Decision | Choice | Why |
|---|---|---|
| Input modality | Live touchscreen (`MotionEvent`) | Gives ground-truth stroke trajectory, order, and direction — no reconstruction needed |
| Training data | None pre-loaded | User teaches by drawing + labeling; system starts empty |
| Architecture | 2-tier `LearningModule` hierarchy, Monty-faithful interfaces | Tier 1: primitives (line/arc/corner). Tier 2: character graphs |
| Messaging | Ported `CmpMessage`/`CmpGoal`, matching Monty's CMP `Message`/`Goal` fields | Same schema at every tier — the "repeating computational unit" principle, and sets up Phase 8 for free |
| Representation | `GraphObjectModel`/`GraphMemory`, pose relative to object's own frame | Matches TBP's reference-frame principle; supports scale/position tolerance |
| Rotation policy | Small tolerance band only (±10–15°), no full rotation search | Characters are orientation-*defining* (b/d/p/q, 6/9) — unlike 3D objects, rotation is identity, not nuisance |
| Multi-stroke / multi-instance handling | Multiple `GraphObjectModel` variants under one label; merge only within tolerance, via `detectNewObject()` (mirrors Monty's `detect_new_object_k_steps`) | Avoids corrupting a model by force-merging structurally different stroke topologies |
| Stroke order/direction matching | Order- and direction-tolerant matching ($1-Unistroke-inspired) | Real handwriting varies in stroke order/count; don't require exact sequence match |
| Ambiguity handling | Report ties explicitly ("6 or 9?") rather than force a guess | A tie is a correct output of evidence accumulation, not a failure |
| Static/photo input | Out of scope for v1; Phase 8 stretch goal | Requires trajectory reconstruction (skeleton glide) — materially harder, separate milestone |
| Persistence | `LearningModule<State>.state()`/`loadState()`, generic per LM — JSON serialization deferred to Phase 7 | Mirrors Monty's own `state_dict`/`Snapshotable` contract; each LM's state shape is its own business, same as Monty's |
| Module boundary | `lib` (pure Kotlin/JVM, zero Android SDK deps) + `app` (Android) | Brain logic must be unit-testable on the JVM in milliseconds, without an emulator or Robolectric; also keeps the door open to a non-Android host later |

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│ UI Layer (Jetpack Compose)                                   │
│  - Drawing canvas · Live evidence bars · Teach/disambiguate   │
└───────────────┬────────────────────────────────────────────────┘
                │ touch events → RawTouchObservation stream
┌───────────────▼────────────────────────────────────────────────┐
│ SensorModule<RawTouchObservation>                              │
│  step(obs) → CmpMessage  {location, morphologicalFeatures,    │
│               nonMorphologicalFeatures: Any, confidence, ...} │
└───────────────┬────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────┐
│ Tier-1: PrimitiveLM : LearningModule<Unit>                     │
│  matchingStep() → segments into line/arc/corner runs           │
│  getOutput() → CmpMessage? (looks like SM output to Tier-2;    │
│                null when there's nothing new — no placeholder) │
└───────────────┬────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────┐
│ Tier-2: CharacterGraphLM : LearningModule<...>                 │
│  matchingStep() → live evidence accumulation vs. GraphMemory   │
│  getOutput() → CmpMessage? (single best label + confidence —   │
│                a point estimate, same shape an SM would emit)  │
│  evidenceSnapshot() → Map<label, Float> (direct query, not a   │
│                CMP message — see §3.4's note on why)           │
│  receiveVotes() → cross-checks (no-op in v1, active in Phase 8)│
└───────────────┬────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────┐
│ GraphMemory                                                    │
│  Map<label, List<GraphObjectModel>>                            │
│  detectNewObject() / addOrMerge() — merge vs. spawn decision    │
│  GraphMatcher.matchScore() — order/direction-tolerant match     │
│  snapshot() / restore() → in-memory state (JSON in Phase 7)     │
└─────────────────────────────────────────────────────────────────┘
```

Orchestration follows Monty's own step loop: collect observation → route to
SM → step LM (modeling) → step LM (voting) → repeat. See §5 for the code.
(The orchestrator itself — `MontyOrchestrator` — hasn't been built yet;
Phases 0–3 validated the pipeline by driving each tier directly from unit
tests. Wiring it into a real step loop is part of Phase 4.)

---

## 2a. Module Boundary: `lib` (brain) vs `app` (Android)

The codebase is split into two Gradle modules with a hard, enforced boundary
between them — this isn't a package-naming convention, it's a build-level
guarantee:

| Module | Gradle plugin | Contains | May depend on Android SDK? |
|---|---|---|---|
| **`lib`** | `kotlin("jvm")` — a plain Kotlin/JVM module, **not** `com.android.library` | Every "brain" class: `CmpMessage`/`CmpGoal`, the `SensorModule`/`LearningModule` interfaces, `PrimitiveLM`, `CharacterGraphLM`, `GraphObjectModel`/`GraphMemory`/`GraphMatcher` (the orchestrator wiring them together is still Phase 4's job) | **No.** The Android SDK is not even on `lib`'s compile classpath, so an accidental `import android.*` is a compile error, not a lint warning. |
| **`app`** | `com.android.application`, depends on `lib` | Jetpack Compose UI, `MotionEvent` capture, on-device persistence (file/DataStore), and all wiring that constructs `lib`'s orchestrator and feeds it converted observations | Yes — this is where all device-specific I/O lives |

Think of `lib` as the organism's brain and `app` as everything else the
organism needs to act in the world — its sensors (the touchscreen) and its
motor/output systems (rendering, haptics, storage). The brain never touches
the nervous system's hardware directly; it only exchanges plain Kotlin data
(`CmpMessage`, `RawTouchObservation`, each `LearningModule`'s own `State`
type) that `app` translates to and from real device APIs.

Concretely, this means:

- **No Android types cross the `lib` public API, ever.** `SensorModule.step()`
  takes a `RawTouchObservation` — a plain data class of floats/ints — never a
  `MotionEvent`. Converting a `MotionEvent` stream into `RawTouchObservation`s
  (buffering by pointer down/up, arc-length resampling, normalization) is
  `app`'s job, implemented as a thin adapter that itself contains no
  recognition logic, so it doesn't need dedicated test coverage beyond a
  smoke test.
- **Persistence is split the same way.** Each `LearningModule`'s `state()`/
  `loadState()` only produces/consumes plain Kotlin data (e.g.
  `CharacterGraphLM`'s is `Map<String, List<GraphObjectModel>>`). Actual
  JSON serialization and file/DataStore I/O on Android is Phase 7's job,
  living in `app`, which calls `lib` to get the data and then
  writes/reads it.
- **UI state (evidence bars, disambiguation prompts) is derived, not owned,
  by `lib`.** `CharacterGraphLM.evidenceSnapshot()` is a plain direct query
  (current evidence per label) that `app`'s Compose screens render — not
  something carried in a `CmpMessage`, since Monty itself doesn't put
  multi-hypothesis evidence in a message either (see §3.3/§3.4); `lib`
  never imports `androidx.compose.*`.
- **Every class in `lib` is unit-testable with plain JUnit on the JVM** —
  `./gradlew :lib:test` runs with no emulator, no Robolectric, no Android
  Gradle plugin in the loop, in seconds. This is the main practical payoff of
  the split: the entire recognition algorithm (resampling → segmentation →
  graph matching → merge/spawn) can be developed and regression-tested
  headlessly, and `app`'s test surface shrinks to a handful of thin adapters
  and UI smoke tests (see §6).

When adding a feature, default to putting the logic in `lib` and ask
explicitly whether any part of it truly needs an Android API (e.g. reading a
`MotionEvent`, writing a file, showing a Compose dialog) — if not, it belongs
in `lib`.

---

## 3. Core Interfaces (Monty-faithful)

### 3.1 CMP message — mirrors `tbp.monty.cmp.Message` / `Goal`

```kotlin
enum class SenderType { SM, LM }

data class MorphologicalFeatures(
    val poseVectors: Array<FloatArray>,   // 2x2 for our 2D domain (Monty: 3x3)
    val poseFullyDefined: Boolean
)

open class CmpMessage(
    val location: FloatArray?,             // (x, y) — Monty: (x, y, z)
    val morphologicalFeatures: MorphologicalFeatures?,
    val nonMorphologicalFeatures: Any,     // see below — not Map<String, Any>
    val confidence: Float,                 // [0, 1]
    val passMessage: Boolean,
    val senderId: String,
    val senderType: SenderType,
    val processFeaturesInLm: Boolean
) {
    fun getPoseVectors(): Array<FloatArray>? = morphologicalFeatures?.poseVectors
    fun isFromSm(): Boolean = senderType == SenderType.SM
}

/** Used by Phase-8 hypothesis-directed glide branching; unused (but present) in v1. */
class CmpGoal(
    location: FloatArray?,
    morphologicalFeatures: MorphologicalFeatures?,
    nonMorphologicalFeatures: Any,
    confidence: Float,
    passMessage: Boolean,
    senderId: String,
    senderType: SenderType,
    processFeaturesInLm: Boolean,
    val goalTolerances: Map<String, Any>?, // matches Monty's own dict[str, Any] | None here
    val info: Map<String, Any>? = null
) : CmpMessage(location, morphologicalFeatures, nonMorphologicalFeatures,
                confidence, passMessage, senderId, senderType, processFeaturesInLm)
```

**`nonMorphologicalFeatures: Any`, not `Map<String, Any>` — and no
`getFeatureByName()`.** Real Monty's `non_morphological_features` is a
Python `dict[str, Any]`, read via string keys (`get_feature_by_name`).
Kotlin can do better without giving up message uniformity: each producer
declares its own tiny marker interface for its own payload shape —
`StrokeFeatures` (`curvature`/`strokeIndex`/`orderInStroke`, from
`TouchSensorModule`), `PrimitiveFeatures` (`type`/`startIndex`/`endIndex`/
`strokeIndex`, from `PrimitiveLM`) — and a consumer does one typed
`is`/`as` check on the whole payload instead of many stringly-typed,
unsafely-cast map lookups. `CmpMessage` itself stays **non-generic**
deliberately: genericizing it over the payload type (an earlier detour this
project took and reverted) would mean `List<CmpMessage>` couldn't stay
uniform across senders — exactly the property `matchingStep`/`receiveVotes`
need, and the same reason real Monty keeps one concrete `Message` class
rather than parameterizing it.

### 3.2 `SensorModule` — mirrors `abstract_monty_classes.SensorModule`

```kotlin
data class RawTouchObservation(
    val position: FloatArray,   // (x, y), normalized
    val tangentAngle: Float,
    val curvature: Float,
    val strokeIndex: Int,
    val orderInStroke: Int
)

interface SensorModule<T> {
    val sensorId: String
    fun step(observation: T): CmpMessage
    fun preEpisode()
    fun postEpisode()
}
```

Generic over the observation type so each concrete sensor
(`TouchSensorModule : SensorModule<RawTouchObservation>`, the only one so
far) declares its own input type rather than every caller unsafely casting.

### 3.3 `LearningModule` — mirrors `abstract_monty_classes.LearningModule`

```kotlin
enum class ExperimentMode { TRAIN, EVALUATE }

interface LearningModule<State> {
    val lmId: String
    fun matchingStep(messages: List<CmpMessage>)   // Monty: matching_step — the modeling step
    fun receiveVotes(votes: List<Any>)             // Monty: receive_votes — votes aren't CmpMessages
    fun sendOutVote(): Any?                        // Monty: send_out_vote — lateral vote, producer-defined shape
    fun getOutput(): CmpMessage?                   // Monty: get_output — single-hypothesis feed-forward output
    fun preEpisode()
    fun postEpisode()
    fun setExperimentMode(mode: ExperimentMode)
    fun state(): State                             // save/load, mirrors Monty's Snapshotable contract
    fun loadState(state: State)
}
```

This interface went through a real correction mid-project, worth recording
because it's easy to get wrong by analogy: an earlier version collapsed
`getOutput`/`sendOutVote` into one `sendOutVote(): CmpMessage` method.
Reading real Monty's `abstract_monty_classes.py` and
`evidence_matching/learning_module.py` directly showed this was wrong on
two counts:

- **`get_output()` and `send_out_vote()` are different methods with
  different purposes.** `get_output()` is the feed-forward message that
  flows up the hierarchy — always a single-hypothesis point estimate,
  structurally identical to what an SM emits (Monty's own comment: "same
  format... to keep the messaging protocol consistent"). `send_out_vote()`
  is the *lateral* vote exchanged between sibling LMs at the same tier —
  a completely different, non-`Message`-shaped structure (see §3.4).
- **`send_out_vote`/`receive_votes` are typed `Any`/`Collection[Any]` in
  Monty itself, deliberately.** Different concrete LMs vote in genuinely
  different shapes: `EvidenceGraphLM.send_out_vote()` returns
  `Dict[graph_id, List[Message]]` (hypotheses it's confident in, with full
  pose+evidence), while `GraphLM.send_out_vote()` returns `Set[graph_id]`
  (objects it's *ruled out* — an inhibitory signal). A shared interface
  can't fix one return type across implementations that disagree this
  fundamentally about what a vote is, so Monty's abstract base class
  doesn't try to, and neither does this port. `Any` here isn't a shortcut —
  it's the same reasoning that keeps `CmpMessage` itself non-generic:
  Monty's own orchestrator (`MontyBase._vote()`) holds all LMs in one flat,
  heterogeneous list and shuffles vote payloads between arbitrary pairs of
  them by index, so genericizing per-LM vote types would just relocate the
  inevitable type erasure to that call site while adding syntax everywhere
  else.

`state()`/`loadState()` also generalized from `Map<String, Any>` to a
generic `State` type param — each LM's state shape is its own business
(`PrimitiveLM`'s is `Unit`, stateless; `CharacterGraphLM`'s is
`Map<String, List<GraphObjectModel>>`, its actual learned memory).

### 3.4 Tier implementations

```kotlin
class PrimitiveLM(override val lmId: String) : LearningModule<Unit> {
    // Online state machine (matches the orchestrator's per-point step loop —
    // matchingStep/getOutput called once per point, not once per stroke): a
    // run of points buffers until one breaks its line/arc/corner hypothesis,
    // at which point it's queued and drained by the next getOutput() call(s).
    // A run ending and a corner starting can both complete on the same point,
    // so finished runs are queued rather than held in one slot. A pen lift
    // between strokes of the same character is *not* a separate episode
    // (see §3.6) — it's a strokeIndex discontinuity inside this same
    // message stream, and matchingStep force-breaks the open run when it
    // sees one, the same way Monty signals sensor discontinuities
    // (on/off-object) as a per-message flag (`use_state`) rather than a
    // distinct lifecycle scope per tier.
    override fun matchingStep(messages: List<CmpMessage>) { /* decode + segment; a strokeIndex change force-breaks the open run, see PrimitiveLM.kt */ }
    override fun receiveVotes(votes: List<Any>) { /* no-op in v1 */ }
    override fun sendOutVote(): Any? = null // no siblings to vote with in v1
    override fun getOutput(): CmpMessage? = TODO("drain the queued finished-run messages")
    override fun preEpisode() { /* reset run-tracking state for the new character */ }
    override fun postEpisode() { /* flush whatever run is still open at the end of the character */ }
    override fun setExperimentMode(mode: ExperimentMode) {}
    override fun state() = Unit // stateless across episodes
    override fun loadState(state: Unit) {}
}

class CharacterGraphLM(
    override val lmId: String,
    private val memory: GraphMemory
) : LearningModule<Map<String, List<GraphObjectModel>>> {
    // matchingStep: each incoming primitive extends this episode's node
    // buffer; GraphMatcher.partialMatchScore scores that growing shape
    // against every stored model's same-length window — live evidence
    // accumulation, not just once the stroke completes.
    override fun matchingStep(messages: List<CmpMessage>) { /* see CharacterGraphLM.kt */ }
    override fun receiveVotes(votes: List<Any>) { /* active in Phase 8 */ }
    override fun sendOutVote(): Any? = null // no siblings to vote with in v1
    override fun getOutput(): CmpMessage? = TODO("single best label + confidence, see note below")
    override fun preEpisode() { /* clear the node buffer */ }
    override fun postEpisode() { /* snapshot the completed graph for teach() */ }
    override fun setExperimentMode(mode: ExperimentMode) {}
    override fun state() = memory.snapshot()
    override fun loadState(state: Map<String, List<GraphObjectModel>>) = memory.restore(state)

    /** Direct introspection for the UI (live evidence bars, tie detection) — see note below. */
    fun evidenceSnapshot(): Map<String, Float> = TODO()

    /**
     * Recognition decision, mirroring Monty's `get_possible_matches()` /
     * `_threshold_possible_matches()` (`evidence_matching/learning_module.py`):
     * labels within [xPercentThreshold]% of the max evidence are "possible
     * matches" — 0 means no match, 1 means a confident recognition, 2+ means
     * a genuine tie. Simplified from Monty's own mean/std branching (not
     * needed at this evidence scale), same spirit as detectNewObject's
     * already-simplified scoring (§3.5).
     */
    fun possibleMatches(xPercentThreshold: Float = 10f): List<String> = TODO()

    /** Combines [possibleMatches] + [evidenceSnapshot] into the three-way UI decision, see RecognitionResult below. */
    fun recognitionResult(): RecognitionResult = TODO()

    /** This app's ground-truth substitute: a human teaches by drawing + labeling (§1). */
    fun teach(label: String) { TODO() }
}

/**
 * The three-way outcome the teach/recognize UI branches on (Phase 4).
 * Mirrors Monty's own `possible_matches`-driven terminal states
 * (`evidence_matching/learning_module.py`), not an app-invented concept:
 * zero possible matches is Monty's own "no_match" terminal state, one is a
 * normal convergence, and 2+ is Monty's own multi-hypothesis case — this app
 * just surfaces that last case as a question instead of forcing a guess.
 */
sealed class RecognitionResult {
    object Unknown : RecognitionResult()

    data class Recognized(val label: String, val confidence: Float) : RecognitionResult()

    data class Ambiguous(val labels: List<String>, val evidence: Map<String, Float>) : RecognitionResult()
}
```

**Why `getOutput()` returns one label + confidence, not a full evidence
map.** The first design here put a `Map<String, Float>` (evidence for
every label) inside `getOutput()`'s `nonMorphologicalFeatures`. Checking
real Monty's actual `get_output()` showed this doesn't match: it collapses
to `mlh` (the single most-likely-hypothesis) — one `object_id` feature, one
confidence — never a distribution, at any tier. Multi-hypothesis
communication in Monty is `send_out_vote()`'s job, and that's a
fundamentally different, non-`Message` structure (see §3.3). So
`CharacterGraphLM.getOutput()` was corrected to match: a single-hypothesis
point estimate. The full per-label breakdown the UI actually needs (live
bars, "is this a 6 or a 9?" tie detection) is `evidenceSnapshot()` — a
plain method a caller queries directly, the same way Monty's own
logging/experiment harness reads an LM's internal hypothesis state directly
rather than through a `Message`.

**Known asymmetry, not yet fixed:** only `CharacterGraphLM` has real
learned memory (`GraphMemory`, evidence accumulation, merge/spawn).
`PrimitiveLM` classifies with fixed geometric thresholds
(`CORNER_CURVATURE_THRESHOLD`, `LINE_ANGLE_TOLERANCE`, etc.), not a learned
graph model of its own — it satisfies the `LearningModule` *interface* but
isn't really a *learning* module yet. In real Monty, every LM at every
tier builds and matches against its own learned graph memory; this port
simplifies Tier 1 because primitives (line/arc/corner) are a small,
closed, geometrically-definable vocabulary, unlike open-ended taught
characters — there's nothing a fixed rule can't already capture. Revisit
if primitive-level ambiguity (a segment that's genuinely borderline
line/arc) turns out to matter once real handwriting is tested against it
(Phase 5).

### 3.5 Graph memory — mirrors `GraphObjectModel` / `GraphMemory`

```kotlin
data class GraphNode(
    val id: Int,
    val location: FloatArray,        // relative to the character's own centroid
    val absoluteAngle: Float,        // tangent angle in the character's own drawing-order frame
    val primitiveType: PrimitiveType // typed, not a nonMorphologicalFeatures-style map — see §3.1's note
)

data class GraphEdge(
    val fromNode: Int,
    val toNode: Int,
    val displacement: FloatArray
    // No per-edge rotation tolerance: matching tolerance is centralized in
    // GraphMatcher instead of stored per-edge — nothing needed a per-edge
    // band, and it's not worth modeling a shape v1 doesn't use.
)

data class GraphObjectModel(
    val label: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,      // sequential chain; multi-stroke is just concatenation in drawing order
    var exemplarCount: Int
)

class GraphMemory {
    private val models = mutableMapOf<String, MutableList<GraphObjectModel>>()

    /** Mirrors detect_new_object_k_steps / detect_new_object_exponential —
     *  the merge-vs-spawn decision. */
    fun detectNewObject(candidate: GraphObjectModel, label: String): Boolean {
        val existing = models[label] ?: return true
        val bestScore = existing.maxOfOrNull { GraphMatcher.matchScore(it, candidate) } ?: 0f
        return bestScore < MERGE_THRESHOLD
    }

    fun addOrMerge(candidate: GraphObjectModel, label: String) {
        val variants = models.getOrPut(label) { mutableListOf() }
        val bestIndex = variants.indices.maxByOrNull { GraphMatcher.matchScore(variants[it], candidate) }
        val bestScore = bestIndex?.let { GraphMatcher.matchScore(variants[it], candidate) } ?: 0f
        if (bestIndex == null || bestScore < MERGE_THRESHOLD) {
            variants.add(candidate)
        } else {
            variants[bestIndex] = mergeInto(variants[bestIndex], candidate) // real averaging, not a TODO
        }
    }

    fun candidatesForLabel(label: String): List<GraphObjectModel> = models[label] ?: emptyList()
    fun allLabels(): Set<String> = models.keys

    fun snapshot(): Map<String, List<GraphObjectModel>> = models.mapValues { it.value.toList() }
    fun restore(snapshot: Map<String, List<GraphObjectModel>>) {
        models.clear()
        snapshot.forEach { (label, variants) -> models[label] = variants.toMutableList() }
    }

    /** Averages candidate's nodes (aligned to target's own order) into the stored model. */
    private fun mergeInto(target: GraphObjectModel, candidate: GraphObjectModel): GraphObjectModel {
        TODO("see GraphMemory.kt: weighted position/circular-angle averaging, exemplarCount + 1")
    }

    companion object { const val MERGE_THRESHOLD = 0.75f }
}
```

#### `GraphMatcher` — order/direction-tolerant matching (the part §3.5's original sketch left as `TODO`)

Full match (`matchScore`, used at merge/spawn time) requires equal node
count, then brute-forces every starting offset *and* both directions
(forward/reversed — same spirit as the `$1` Unistroke Recognizer this
project's README cites) looking for an alignment whose primitive-type
sequence matches exactly, scored by how close corresponding nodes'
angles/positions are. `partialMatchScore` (live evidence, mid-stroke) is
the same search comparing a growing prefix against a same-length window of
a stored model, powering `CharacterGraphLM`'s live accumulation.

The one non-obvious trick: comparing angles across *different* alignments
only works if each side is re-baselined to its own first node before
comparing, rather than compared as stored — `GraphNode.absoluteAngle` is
only meaningful relative to *some* reference, and which node is "first"
changes with the alignment being tried. Re-baselining also absorbs
direction reversal for free: reading a stored sequence backward and
re-baselining it lands on the same relative-angle sequence a genuine
reverse retrace would reconstruct, with no separate sign-flip logic needed
for the turn direction.

### 3.6 Orchestrator — mirrors `MontyBase`'s step loop

**Not built yet** — Phases 0–3 validated the pipeline by driving each tier
directly from unit tests (see `CharacterGraphLMTest`'s `drive()` helper,
which is essentially this loop already, minus a real class around it).
Building `MontyOrchestrator` for real is Phase 4's job, wiring `app`'s touch
input to it.

**Episode boundary is uniform across every tier — not per-LM-type.** Real
Monty's `MontyExperiment.run_episode()` calls `MontyBase.reset()` exactly
once per episode, and `reset()` loops over *every* sensor module and
learning module identically (`monty_base.py`'s `reset()`) — nothing in
Monty lets one LM define a different episode scope than another; the
experiment/environment owns that boundary, not the LM. Since one Monty
episode is one full object presentation (potentially many sensor movement
steps before `is_done`), this app's matching unit is one full
**character** — potentially several strokes — not one stroke. So
`preEpisode()`/`postEpisode()` on both `PrimitiveLM` and `CharacterGraphLM`
bound one character, together, always. (An earlier draft of this section
briefly proposed a stroke-scoped episode for `PrimitiveLM` and a
character-scoped one for `CharacterGraphLM` — worth recording as a mistake
caught by checking Monty's actual source rather than reasoning about it
from the class names alone, same category of correction as §3.3's
`getOutput`/`sendOutVote` split.)

The pen lift between strokes of the same character is therefore *not* a
second, smaller episode — it's an ordinary discontinuity riding in the
message stream, the same way Monty itself signals a sensor discontinuity
(leaving an object, `on_object` going false) via a per-observation flag
(`use_state`, `sensor_modules.py`) rather than a separate lifecycle scope.
Here, `StrokeFeatures.strokeIndex` already carries exactly this:
`PrimitiveLM.step()` force-breaks its open run whenever `strokeIndex`
changes — the same code path as hitting a corner — with no new message
field needed.

**A second, independent issue surfaced while implementing this: per-stroke
normalization silently destroys cross-stroke position information.**
`StrokePreprocessor.normalize()` centers+scales whatever point list it's
given to *its own* centroid/bounding-radius. If each stroke of a
multi-stroke character were normalized independently, every stroke's
primitives would land near its own local origin regardless of where the
strokes actually sit relative to each other — `GraphMatcher`'s
position-distance term would then carry no real cross-stroke
discriminative signal (e.g. it couldn't distinguish a "+" from two
unrelated strokes drawn far apart). The fix: normalize once across every
stroke drawn so far in the character, using `StrokePreprocessor`'s
already-separated `resampleByArcLength`/`normalize`/`tangentsAndCurvatures`
primitives rather than its all-in-one `preprocess()` (tangent/curvature are
invariant to uniform translate+scale, so they're still computed per stroke
on the unnormalized resampled points — only `position` needs the shared
value). Since that shared normalization can shift whenever a stroke is
added or removed, there's no way to incrementally patch already-computed
primitives, so the orchestrator holds each character's raw stroke points
and replays the whole pipeline from scratch on every change. This is cheap
at this data scale (a handful of strokes, tens of points each) and, as a
direct consequence, gives the orchestrator natural, correct ownership of
undo/clear too — since it already holds the authoritative stroke list,
"undo" is just "drop the last stroke and replay," with no separate replay
logic needed on the `app` side.

```kotlin
class MontyOrchestrator(
    private val sensorModule: SensorModule<RawTouchObservation>,
    private val tier1: PrimitiveLM,
    private val tier2: CharacterGraphLM,
) {
    private val strokePoints = mutableListOf<List<RawPoint>>()

    /** Starts a new character episode, discarding any strokes from a previous one that was never ended. */
    fun beginCharacter() {
        strokePoints.clear()
        replay()
    }

    /** Adds one completed stroke's raw points to the still-open character and recomputes evidence. */
    fun stepStroke(points: List<RawPoint>) {
        strokePoints.add(points)
        replay()
    }

    /** Drops the most recently added stroke (if any) and recomputes evidence. */
    fun undoLastStroke() {
        if (strokePoints.isEmpty()) return
        strokePoints.removeAt(strokePoints.lastIndex)
        replay()
    }

    /** Discards every stroke drawn so far in the current character. */
    fun clearCharacter() {
        strokePoints.clear()
        replay()
    }

    /** Ends the character episode — the one point where tier2's postEpisode() truly fires. */
    fun endCharacter(): RecognitionResult {
        tier2.postEpisode()
        return tier2.recognitionResult()
    }

    fun teach(label: String) = tier2.teach(label)

    /**
     * Recomputes the whole character from scratch. tier1.preEpisode()/
     * postEpisode() fire on every replay — harmless, since PrimitiveLM is
     * fully stateless across calls — so evidence stays live as strokes are
     * added or removed. tier2.postEpisode() deliberately does NOT fire
     * here: its only consequential effect (snapshotting for teach()) is
     * reserved for the true end of the character, in endCharacter().
     */
    private fun replay() {
        tier1.preEpisode()
        tier2.preEpisode()
        for (observations in buildNormalizedObservations()) {
            for (observation in observations) {
                tier1.matchingStep(listOf(sensorModule.step(observation)))
                drainTier1IntoTier2()
            }
        }
        tier1.postEpisode()
        drainTier1IntoTier2()
    }

    private fun drainTier1IntoTier2() {
        val outputs = generateSequence { tier1.getOutput() }.toList()
        if (outputs.isNotEmpty()) tier2.matchingStep(outputs)
        tier2.receiveVotes(emptyList()) // Phase 8: populated by sibling glide LMs
    }

    /** Resamples each stroke independently, but normalizes them together — see the note above. */
    private fun buildNormalizedObservations(): List<List<RawTouchObservation>> {
        if (strokePoints.isEmpty()) return emptyList()
        val resampledPerStroke = strokePoints.map {
            StrokePreprocessor.resampleByArcLength(it, StrokePreprocessor.DEFAULT_RESAMPLE_COUNT)
        }
        val flatNormalized = StrokePreprocessor.normalize(resampledPerStroke.flatten())
        var offset = 0
        return resampledPerStroke.mapIndexed { strokeIndex, resampled ->
            val normalizedChunk = flatNormalized.subList(offset, offset + resampled.size)
            val tangentsAndCurvatures = StrokePreprocessor.tangentsAndCurvatures(resampled)
            offset += resampled.size
            normalizedChunk.mapIndexed { i, point ->
                val (tangentAngle, curvature) = tangentsAndCurvatures[i]
                RawTouchObservation(point.toFloatArray(), tangentAngle, curvature, strokeIndex, i)
            }
        }
    }
}
```

Known, accepted simplification: every stroke is resampled to the same
fixed point count before being pooled for shared normalization, so a short
stroke and a long stroke contribute equally many points to the
centroid/scale estimate rather than being weighted by their actual arc
length — worth revisiting in Phase 5 if very unevenly-sized strokes (e.g.
a dot plus a long stroke) turn out to matter.

---

## 4. Mapping table: faithful port vs. deliberate simplification

| Monty concept | This app | Faithful or simplified? |
|---|---|---|
| CMP `Message` fields | `CmpMessage` — same fields | **Faithful** |
| 3×3 pose vectors, 3D location | 2×2 pose vectors, 2D location | **Simplified** — 2D domain |
| `non_morphological_features: dict[str, Any]`, read by string key | `nonMorphologicalFeatures: Any`, one small marker interface per producer (`StrokeFeatures`, `PrimitiveFeatures`) | **Improved on**, not simplified — same message uniformity, no stringly-typed lookups or unsafe per-field casts Kotlin's type system doesn't need |
| `SensorModule` reading a simulated/real sensor | `SensorModule<T>` reading resampled touch points | **Faithful interface**, different observation source |
| `matching_step` / `receive_votes` | `matchingStep` / `receiveVotes` | **Faithful** |
| `get_output()` / `send_out_vote()` split; the latter typed `Any` because concrete LMs vote in different shapes | `getOutput(): CmpMessage?` / `sendOutVote(): Any?` | **Faithful** — corrected mid-project after an earlier version collapsed both into one method (see §3.3) |
| `state_dict()`/load for persistence | `state()`/`loadState()`, generic per LM — JSON deferred to Phase 7 | **Faithful contract**, simple backing store |
| `GraphObjectModel` / `GraphMemory` | Same names, 2D fields | **Faithful** |
| `detect_new_object_k_steps` merge/spawn logic | `detectNewObject()` | **Faithful concept**, simplified scoring function |
| Every LM at every tier builds/matches its own learned graph memory | Only `CharacterGraphLM` (Tier 2) has one; `PrimitiveLM` (Tier 1) uses fixed geometric thresholds | **Simplified** — primitives are a small, closed, geometrically-definable vocabulary, unlike open-ended taught characters (see §3.4's note) |
| Motor system + simulator driving a sensor | None — touch input replaces the motor system | **Not ported** — the human *is* the motor system |
| Multiple LMs voting via lateral CMP | Single LM per tier in v1; multiple `PrimitiveLM`s voting in Phase 8 | **Deferred**, same interface supports it |
| Goal-State Generators / `CmpGoal` | Present as a class shape, unused until Phase 8 | **Stubbed** |
| Episode = one object presentation, uniform across every SM/LM (`MontyBase.reset()`) | Episode = one character (possibly multi-stroke), uniform across both tiers; stroke boundaries are a `strokeIndex` discontinuity in the message stream, not a separate lifecycle scope (§3.6) | **Faithful** |
| `get_possible_matches()` / `_threshold_possible_matches()` deciding no-match/converged/multi-hypothesis | `CharacterGraphLM.possibleMatches()` / `recognitionResult()` | **Faithful concept**, simplified scoring (straight percent-of-max, no mean/std branching) |

Be honest with yourself about the last two rows while building v1: most of
the voting/goal machinery is architecturally present but does nothing yet,
because a single touch-drawn character doesn't need it. The payoff is that
Phase 8 becomes "add LM instances and populate `receiveVotes`," not a
redesign.

---

## 5. Phased Build Plan

### Phase 0 — Project Setup (½–1 day) ✅ Done
New Android Studio project with **two Gradle modules from day one**: `lib`
(`kotlin("jvm")` plugin, zero Android dependencies) and `app`
(`com.android.application`, depends on `lib`) — see §2a. Set up Jetpack
Compose in `app`. `Canvas` composable in `app` capturing `MotionEvent` and
rendering strokes live. No recognition logic yet.
**Exit:** draw a character, see it rendered, dump raw stroke points to
Logcat; `./gradlew :lib:test` runs (even with zero tests) to confirm the
module boundary compiles with no Android SDK on its classpath.

### Phase 1 — SensorModule: Capture & Resampling (1–2 days) ✅ Done
Buffer strokes by pointer down/up. Implement arc-length resampling to N
evenly-spaced points (N ≈ 32–64). Normalize (translate to centroid, scale to
reference size). Implement `SensorModule.step()` to emit `CmpMessage`s.
**Exit:** same letter fast/slow, small/large → near-identical point sequences
after resample+normalize. (`StrokePreprocessorTest` drives this directly:
dense vs. sparse sampling and 1x vs. 2x scale of the same path resample to
near-identical points.)

### Phase 2 — `PrimitiveLM` (Tier-1) (2–4 days) ✅ Done
Implement `matchingStep()`: compute local tangent angle per point, segment
into line/arc/corner runs by tangent stability. `getOutput()` emits the
primitive as a `CmpMessage` with relative (not absolute-screen) tangent angle.
**Exit:** unit tests on synthetic point arrays — a straight line, an "L", a
"C" — produce the expected primitive sequences (`PrimitiveLMTest`). Two real
bugs surfaced only by running the actual synthetic geometry, not by
reasoning about it on paper: (1) `getOutput()`/`sendOutVote()` needing to be
separate methods, and a finished run plus a new corner completing on the
same point, meant queuing outputs rather than holding one slot; (2) a real
corner's turn lands on more than one resampled point, so consecutive
high-curvature points had to merge into one `CORNER` run instead of each
emitting its own primitive.

### Phase 3 — `CharacterGraphLM` (Tier-2) + `GraphMemory` (3–5 days) ✅ Done
Build `GraphObjectModel` from a character's primitives (nodes = primitives
w/ relative pose to centroid; edges = relations incl. cross-stroke). Implement
`GraphMatcher.matchScore()` — order/direction-tolerant point-set matching.
Implement live evidence accumulation as strokes complete, not just at the end.
**Exit:** teach 2–3 labels, draw a fresh instance, correct label scores
highest — validated end to end by `CharacterGraphLMTest` driving the full
Phase 1→2→3 pipeline (unit test, not a debug button; the UI hook for
teaching is Phase 4's job). `mergeInto` and `matchScore` are real
implementations now, not the original sketch's `TODO()`s — see §3.5.

### Phase 4 — Teach & Recognize UI Loop (2–3 days)
Build `MontyOrchestrator` for real (§3.6) and wire `app`'s touch input to
it. The core design point (§3.6): one episode is one full **character**,
uniform across both tiers — matching Monty's own `MontyBase.reset()`, which
applies identically to every SM/LM regardless of hierarchy position.
Strokes are steps *within* that episode, not episodes of their own; a pen
lift is a `strokeIndex` discontinuity `PrimitiveLM` reacts to directly,
mirroring how Monty signals sensor discontinuities (`use_state`) as message
data rather than a separate lifecycle call.

**`lib` additions:**
- `MontyOrchestrator` (§3.6): `beginCharacter()` / `stepStroke(points)` /
  `undoLastStroke()` / `clearCharacter()` / `endCharacter(): RecognitionResult`
  / `teach(label)`. Normalizes once across every stroke drawn so far in the
  character (not per stroke — see §3.6's note on why), replaying the whole
  pipeline from scratch on every change; this also means `undoLastStroke()`/
  `clearCharacter()` live here, not as `app`-side replay logic.
- `PrimitiveLM.step()`: detect a `strokeIndex` change against the buffered
  run and force a break (same code path as a corner ending a line), so a
  run never silently spans a pen lift. Update its `preEpisode`/
  `postEpisode` doc comments — they now bound one character, not one stroke.
- `CharacterGraphLM.possibleMatches(xPercentThreshold: Float = 10f): List<String>`
  — port of Monty's `get_possible_matches()`/`_threshold_possible_matches()`
  (`evidence_matching/learning_module.py`), simplified to straight
  percent-of-max thresholding (no mean/std branching — same spirit as
  `detectNewObject`'s already-simplified scoring, §3.5). 0 matches → no
  match, 1 → confident recognition, 2+ → genuine tie.
- `RecognitionResult` sealed class (`Unknown` / `Recognized(label, confidence)`
  / `Ambiguous(labels, evidence)`), built from `possibleMatches()` +
  `evidenceSnapshot()` via `CharacterGraphLM.recognitionResult()`, and
  returned by `MontyOrchestrator.endCharacter()`.
- Tests: a `PrimitiveLMTest` case for a stroke gap not bleeding into a run;
  `possibleMatches`/`recognitionResult` unit cases (empty memory → Unknown,
  one dominant label → Recognized, a deliberately close pair → Ambiguous);
  a new `MontyOrchestratorTest` driving a multi-stroke synthetic shape
  (e.g. a 2-stroke "+") end to end — teach it, then confirm a fresh,
  differently-drawn instance still scores it highest — plus the existing
  single-stroke cases as a regression check.

**`app` wiring:**
- `RecognizerViewModel` (`androidx.lifecycle.ViewModel` — already a
  dependency, unused until now) owns one long-lived `MontyOrchestrator` /
  `GraphMemory` for the process lifetime (Phase 7 adds real persistence
  across process death). Exposes: the strokes drawn so far this character,
  live `evidenceSnapshot()` for the bars, and the current
  `RecognitionResult?` once "Done" is pressed.
- `DrawingToolbar`: add a "Done" button (alongside Undo/Clear) that closes
  the current character (`endCharacter()`) and triggers the result panel.
  Undo/Clear delegate straight to `MontyOrchestrator.undoLastStroke()`/
  `clearCharacter()` — no separate replay logic needed on the `app` side.
- Live evidence bars: a small composable rendering `evidenceSnapshot()` as
  per-label bars, refreshed at least once per completed stroke (finer,
  per-point updates are a possible later refinement, not required for the
  exit criterion).
- Result panel, one of three variants driven by `RecognitionResult`:
  - `Unknown` → a label text field + "Teach" button → `teach(label)`.
  - `Recognized(label, confidence)` → "Is this '<label>'?" with **Confirm**
    (calls `teach(label)` again, reinforcing the matched variant) and
    **Correct** (reveals a text field for the right label, calls
    `teach(correctLabel)`).
  - `Ambiguous(labels)` → one button per candidate label plus a "something
    else" text field, all calling `teach(chosenLabel)` — the
    disambiguation question itself, not a forced top-1 guess.
- Wire `DrawingScreen`/`DrawingCanvas` to `RecognizerViewModel` instead of
  local `remember` state; drop the Phase-0 log-only `logPreprocessed` call
  now that the real pipeline consumes completed strokes.

**Exit:** on-device, teach a handful of labels including one genuinely
multi-stroke letter (e.g. "t" or "+") and a deliberately ambiguous pair
(e.g. "O"/"0"), and confirm: the multi-stroke label is recognized as one
character from a fresh two-stroke instance, and the ambiguous pair
surfaces a disambiguation prompt rather than a forced guess.

### Phase 5 — Merge/Spawn Tuning (2–3 days)
Tune `GraphMemory.MERGE_THRESHOLD` and `matchScore` against real handwriting
samples — this is Monty's own `detect_new_object_*` mechanism, not a
custom heuristic bolted on afterward.
**Exit:** natural variation of the same label settles into one generalized
model; a structurally different stroke order for the same label spawns a
second variant instead of corrupting the first.

### Phase 6 — Rotation & Orientation Handling (1 day)
Small rotation tolerance only (±10–15°) inside `matchScore`; no full rotation
search. Optional: accelerometer/gravity sanity check for gross device tilt.
**Exit:** an upside-down "6" does not match a right-side-up "9" model.

### Phase 7 — Polish (ongoing)
JSON serialization of `CharacterGraphLM.state()` on app start/stop (the
in-memory `state()`/`loadState()` contract already exists as of Phase 3 —
this phase adds the actual file I/O, in `app`, per §2a). Debug/inspector
screen listing learned labels, viewable/deletable variants, raw node/edge
data. Basic onboarding for the teach/recognize loop.

### Phase 8 — Stretch: Static Image Input via Multi-Glide Voting
Only after v1 is solid.
1. Import photo → threshold + skeletonize (OpenCV) → build skeleton adjacency
   graph (endpoints = degree 1, junctions = degree ≥3).
2. Run one simulated "glide" `PrimitiveLM` instance per connected
   component/branch, advancing by straightest-continuation at junctions.
3. **Voting for disambiguation:** at an ambiguous junction, use relative-
   position consistency across simultaneously-running glide LMs, plus
   top-down bias from `CharacterGraphLM`'s current leading hypothesis
   (populate `CmpGoal` + `receiveVotes` here — this is exactly what those
   stubs were for), instead of a purely local straightness heuristic.
4. Feed resulting primitive sequences into the **same** `CharacterGraphLM` —
   only works cleanly because `matchScore` is already order/direction-tolerant.

Expected, not-to-be-solved-in-a-first-pass limitations (per the handwriting
trajectory-recovery literature): genuine stroke-order ambiguity in
multi-stroke characters, and ambiguous resolution at true crossing points.
Treat both as ties surfaced to the user, same as the 6/9 case.

---

## 6. Testing Strategy

Testing follows the module split in §2a: almost everything worth testing
lives in `lib` and runs as plain JUnit on the JVM; `app`'s test surface is
deliberately small.

- **`lib` unit tests (`:lib:test`, plain JUnit, JVM only — no emulator, no
  Robolectric) — 38 tests as of Phase 3, across:**
  - `StrokePreprocessorTest` — resampling, normalization, tangent/curvature,
    the fast/slow + small/large invariance exit criterion.
  - `TouchSensorModuleTest`, `CmpMessageTest` — message construction/accessors.
  - `PrimitiveLMTest` — the three synthetic-shape segmentation cases plus
    interface sanity (state, experiment mode, voting no-ops).
  - `GraphMatcherTest` — order/direction tolerance, mismatched-type/count
    rejection, partial-match prefix tracking, on hand-built synthetic graphs.
  - `GraphMemoryTest` — merge-vs-spawn, snapshot/restore round-trips.
  - `CharacterGraphLMTest` — the actual Phase 3 exit criterion end to end
    through the full Phase 1→2→3 pipeline, plus a `getOutput()`/
    `evidenceSnapshot()` coherence check.
  - `MontyOrchestrator` step-loop behavior driven by synthetic multi-stroke
    `RawPoint` sequences, including a pen lift mid-character, once it's
    built (Phase 4) — no real touchscreen or device needed.
  - `state()`/`loadState()` round-trips on plain Kotlin data.
  - Because none of this touches Android, these tests are fast enough to run
    on every save and in CI without a device/emulator.
- **`app` tests (thin adapters + UI only):**
  - `MotionEvent` → `RawTouchObservation` conversion (buffering, resampling
    hookup) — a small, mockable adapter, tested with a couple of synthetic
    `MotionEvent` sequences.
  - Persistence adapter (`lib`'s bytes ↔ Android file/DataStore).
  - Compose UI smoke tests (teach/recognize screen renders, disambiguation
    dialog appears on a tie) — no recognition logic duplicated here.
- **Manual regression set** — once ~10 labels are taught, keep a fixed
  redraw script to catch regressions after any threshold/algorithm change.
- **Deliberate ambiguity tests** — at least one pair expected to tie (your
  own "6"/"9" or "O"/"0") to verify the disambiguation UI triggers rather
  than forcing a guess.

---

## 7. Key Risks / Open Questions

- **Threshold tuning (`MERGE_THRESHOLD`, tolerance bands, tie-margin) is the
  main day-to-day effort** — budget real time against your own handwriting.
  A first real-usage pass already surfaced one structural gap ahead of
  Phase 5: `PrimitiveLM`'s corner/arc/line thresholds were tuned against
  clean synthetic test geometry, and real finger jitter reads as large
  angular noise (a small lateral wobble over a short resampled step swings
  `atan2` wildly), spuriously segmenting a single straight stroke into
  several lines/arcs. Fixed via `StrokePreprocessor.smooth()` (a
  boundary-aware moving average damping jitter before tangent/curvature
  estimation amplifies it) and by comparing `decide()`'s line-consistency
  check against the run's circular-mean tangent rather than a single
  reference point (so per-point noise cancels instead of poisoning every
  later comparison) — see `PrimitiveLM.kt`/`StrokePreprocessor.kt`. The
  numeric thresholds themselves (`LINE_ANGLE_TOLERANCE`,
  `CORNER_CURVATURE_THRESHOLD`, etc.) are untouched; further calibration
  against real handwriting is still this phase's job.
- **Multi-stroke composition remains the least theoretically settled part**
  of TBP itself (flagged as immature even in Monty) — the variant-based
  fallback is a pragmatic substitute for genuine compositional
  generalization, not a solved version of it.
- **True symmetric ambiguities (6/9, O/0) are expected, permanent outcomes**,
  not defects — the app's job is to surface them cleanly, not eliminate them.
- **Most of the CMP/voting/goal machinery is dormant until Phase 8** — don't
  mistake unused interface surface for wasted effort; it's what makes Phase 8
  additive instead of a rewrite.
- **Tier 1 isn't really a learning module yet** (§3.4/§4) — it classifies
  primitives with fixed geometric thresholds, not a learned graph model the
  way Tier 2 does. Fine for a small closed vocabulary like line/arc/corner,
  but revisit if Phase 5's real-handwriting tuning turns up genuine
  primitive-level ambiguity (a segment that's a borderline
  line/arc call) that a hard threshold can't represent — the fix would be
  reporting a per-type confidence rather than one committed type, decided
  with a concrete need in hand rather than speculatively now.
- **When a design decision here is ambiguous, check real Monty's source
  before inventing something new** (see `CLAUDE.md` for the local reference
  checkout) — this app should stay legible to anyone who already knows
  Monty. The `getOutput`/`sendOutVote` split, dropping `getFeatureByName`
  for typed payloads, and correcting the orchestrator's episode boundary to
  be uniform across tiers instead of per-LM-type (§3.6) all came from doing
  exactly this after an earlier design had drifted from Monty's actual
  behavior.
