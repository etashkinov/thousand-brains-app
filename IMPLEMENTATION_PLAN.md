# Implementation Plan — TBP-Inspired Handwriting Recognition App

**Platform:** Android (Kotlin) · **Status:** Design complete, ready to build
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
| Stroke order/direction matching | Order- and direction-tolerant matching ($P-point-cloud-inspired) | Real handwriting varies in stroke order/count; don't require exact sequence match |
| Ambiguity handling | Report ties explicitly ("6 or 9?") rather than force a guess | A tie is a correct output of evidence accumulation, not a failure |
| Static/photo input | Out of scope for v1; Phase 8 stretch goal | Requires trajectory reconstruction (skeleton glide) — materially harder, separate milestone |
| Persistence | `stateDict()`/`loadStateDict()` → JSON | Mirrors Monty's own save/load contract |
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
│ SensorModule                                                  │
│  step(obs) → CmpMessage  {location, morphological_features,   │
│               non_morphological_features, confidence, ...}    │
└───────────────┬────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────┐
│ Tier-1: PrimitiveLM : LearningModule                           │
│  matchingStep() → segments into line/arc/corner runs           │
│  sendOutVote() → CmpMessage (looks like SM output to Tier-2)   │
└───────────────┬────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────┐
│ Tier-2: CharacterGraphLM : LearningModule                      │
│  matchingStep() → evidence accumulation vs. GraphMemory        │
│  receiveVotes() → cross-checks (no-op in v1, active in Phase 8)│
└───────────────┬────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────┐
│ GraphMemory                                                    │
│  Map<label, List<GraphObjectModel>>                            │
│  detectNewObject() / addOrMerge() — merge vs. spawn decision    │
│  stateDict() / loadStateDict() → JSON persistence               │
└─────────────────────────────────────────────────────────────────┘
```

Orchestration follows Monty's own step loop: collect observation → route to
SM → step LM (modeling) → step LM (voting) → repeat. See §5 for the code.

---

## 2a. Module Boundary: `lib` (brain) vs `app` (Android)

The codebase is split into two Gradle modules with a hard, enforced boundary
between them — this isn't a package-naming convention, it's a build-level
guarantee:

| Module | Gradle plugin | Contains | May depend on Android SDK? |
|---|---|---|---|
| **`lib`** | `kotlin("jvm")` — a plain Kotlin/JVM module, **not** `com.android.library` | Every "brain" class: `CmpMessage`/`CmpGoal`, the `SensorModule`/`LearningModule` interfaces, `PrimitiveLM`, `CharacterGraphLM`, `GraphObjectModel`/`GraphMemory`, `MontyOrchestrator` | **No.** The Android SDK is not even on `lib`'s compile classpath, so an accidental `import android.*` is a compile error, not a lint warning. |
| **`app`** | `com.android.application`, depends on `lib` | Jetpack Compose UI, `MotionEvent` capture, on-device persistence (file/DataStore), and all wiring that constructs `lib`'s orchestrator and feeds it converted observations | Yes — this is where all device-specific I/O lives |

Think of `lib` as the organism's brain and `app` as everything else the
organism needs to act in the world — its sensors (the touchscreen) and its
motor/output systems (rendering, haptics, storage). The brain never touches
the nervous system's hardware directly; it only exchanges the same plain
Kotlin data (`CmpMessage`, `RawTouchObservation`, `Map<String, Any>`
state) that `app` translates to and from real device APIs.

Concretely, this means:

- **No Android types cross the `lib` public API, ever.** `SensorModule.step()`
  takes a `RawTouchObservation` — a plain data class of floats/ints — never a
  `MotionEvent`. Converting a `MotionEvent` stream into `RawTouchObservation`s
  (buffering by pointer down/up, arc-length resampling, normalization) is
  `app`'s job, implemented as a thin adapter that itself contains no
  recognition logic, so it doesn't need dedicated test coverage beyond a
  smoke test.
- **Persistence is split the same way.** `lib`'s `stateDict()`/
  `loadStateDict()` contract only produces/consumes plain Kotlin
  (`Map<String, Any>`, or a `@Serializable` data class via
  `kotlinx.serialization` — itself pure Kotlin, safe to depend on from
  `lib`). Actual file/DataStore I/O on Android happens in `app`, which calls
  `lib` to get the bytes and then writes/reads them.
- **UI state (evidence bars, disambiguation prompts) is derived, not owned,
  by `lib`.** `lib` exposes plain data (current evidence per label, tie
  detection) that `app`'s Compose screens render; `lib` never imports
  `androidx.compose.*`.
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
    val nonMorphologicalFeatures: Map<String, Any>,
    val confidence: Float,                 // [0, 1]
    val passMessage: Boolean,
    val senderId: String,
    val senderType: SenderType,
    val processFeaturesInLm: Boolean
) {
    fun getFeatureByName(name: String): Any? = nonMorphologicalFeatures[name]
    fun getPoseVectors(): Array<FloatArray>? = morphologicalFeatures?.poseVectors
    fun isFromSm(): Boolean = senderType == SenderType.SM
}

/** Used by Phase-8 hypothesis-directed glide branching; unused (but present) in v1. */
class CmpGoal(
    location: FloatArray?,
    morphologicalFeatures: MorphologicalFeatures?,
    nonMorphologicalFeatures: Map<String, Any>,
    confidence: Float,
    passMessage: Boolean,
    senderId: String,
    senderType: SenderType,
    processFeaturesInLm: Boolean,
    val goalTolerances: Map<String, Any>?,
    val info: Map<String, Any>? = null
) : CmpMessage(location, morphologicalFeatures, nonMorphologicalFeatures,
                confidence, passMessage, senderId, senderType, processFeaturesInLm)
```

### 3.2 `SensorModule` — mirrors `abstract_monty_classes.SensorModule`

```kotlin
data class RawTouchObservation(
    val position: FloatArray,   // (x, y), normalized
    val tangentAngle: Float,
    val curvature: Float,
    val strokeIndex: Int,
    val orderInStroke: Int
)

interface SensorModule {
    val sensorId: String
    fun step(rawObservation: RawTouchObservation): CmpMessage
    fun preEpisode()
    fun postEpisode()
}
```

### 3.3 `LearningModule` — mirrors `abstract_monty_classes.LearningModule`

```kotlin
enum class ExperimentMode { TRAIN, EVALUATE }

interface LearningModule {
    val lmId: String
    fun matchingStep(messages: List<CmpMessage>)   // Monty: matching_step — the modeling step
    fun receiveVotes(votes: List<CmpMessage>)      // Monty: receive_votes — the voting step
    fun sendOutVote(): CmpMessage                  // "defines what data are sent to other LMs"
    fun preEpisode()
    fun postEpisode()
    fun setExperimentMode(mode: ExperimentMode)
    fun stateDict(): Map<String, Any>              // save/load, mirrors Monty's Snapshotable contract
    fun loadStateDict(state: Map<String, Any>)
}
```

### 3.4 Tier implementations

```kotlin
class PrimitiveLM(override val lmId: String) : LearningModule {
    // matchingStep: segment touch-point messages into line/arc/corner runs.
    // sendOutVote(): emits a CmpMessage carrying {"primitive_type": ...} in
    // non_morphological_features and the primitive's relative pose in
    // morphological_features — same shape an SM would emit, per Monty's
    // convention that one tier's LM output looks like SM output to the next.
    override fun matchingStep(messages: List<CmpMessage>) { TODO() }
    override fun receiveVotes(votes: List<CmpMessage>) { /* no-op in v1 */ }
    override fun sendOutVote(): CmpMessage { TODO() }
    override fun preEpisode() { TODO() }
    override fun postEpisode() { TODO() }
    override fun setExperimentMode(mode: ExperimentMode) { TODO() }
    override fun stateDict(): Map<String, Any> = emptyMap() // stateless per-episode
    override fun loadStateDict(state: Map<String, Any>) {}
}

class CharacterGraphLM(
    override val lmId: String,
    val memory: GraphMemory
) : LearningModule {
    // matchingStep: evidence accumulation against memory's stored GraphObjectModels.
    override fun matchingStep(messages: List<CmpMessage>) { TODO() }
    override fun receiveVotes(votes: List<CmpMessage>) { /* active in Phase 8 */ }
    override fun sendOutVote(): CmpMessage { TODO() }
    override fun preEpisode() { TODO() }
    override fun postEpisode() { TODO() }
    override fun setExperimentMode(mode: ExperimentMode) { TODO() }
    override fun stateDict(): Map<String, Any> = memory.stateDict()
    override fun loadStateDict(state: Map<String, Any>) = memory.loadStateDict(state)
}
```

### 3.5 Graph memory — mirrors `GraphObjectModel` / `GraphMemory`

```kotlin
data class GraphNode(
    val id: Int,
    val location: FloatArray,                       // relative to object's own frame
    val poseVectors: Array<FloatArray>,              // 2x2 in our 2D case
    val nonMorphologicalFeatures: Map<String, Any>   // primitive type, etc.
)

data class GraphEdge(
    val fromNode: Int,
    val toNode: Int,
    val displacement: FloatArray,
    val relativeRotationTolerance: ClosedFloatingPointRange<Float>
)

data class GraphObjectModel(
    val label: String,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    var exemplarCount: Int
)

class GraphMemory {
    private val models = mutableMapOf<String, MutableList<GraphObjectModel>>()

    /** Mirrors graph_matching_utils.is_in_ranges. */
    fun isInRanges(value: Float, range: ClosedFloatingPointRange<Float>): Boolean =
        value in range

    /** Mirrors detect_new_object_k_steps / detect_new_object_exponential —
     *  the merge-vs-spawn decision. */
    fun detectNewObject(candidate: GraphObjectModel, label: String): Boolean {
        val existing = models[label] ?: return true
        val bestScore = existing.maxOfOrNull { matchScore(it, candidate) } ?: 0f
        return bestScore < MERGE_THRESHOLD
    }

    fun addOrMerge(candidate: GraphObjectModel, label: String) {
        if (detectNewObject(candidate, label)) {
            models.getOrPut(label) { mutableListOf() }.add(candidate)
        } else {
            mergeInto(bestMatch(candidate, label), candidate)
        }
    }

    fun matchScore(stored: GraphObjectModel, candidate: GraphObjectModel): Float {
        TODO("order/direction-tolerant point-set matching")
    }

    fun candidatesForLabel(label: String): List<GraphObjectModel> = models[label] ?: emptyList()
    fun allLabels(): Set<String> = models.keys

    fun stateDict(): Map<String, Any> = mapOf("models" to models)
    fun loadStateDict(state: Map<String, Any>) {
        @Suppress("UNCHECKED_CAST")
        (state["models"] as? Map<String, MutableList<GraphObjectModel>>)?.let {
            models.clear(); models.putAll(it)
        }
    }

    private fun bestMatch(candidate: GraphObjectModel, label: String): GraphObjectModel =
        models[label]!!.maxBy { matchScore(it, candidate) }

    private fun mergeInto(target: GraphObjectModel, candidate: GraphObjectModel) {
        TODO("widen tolerance bands / average node positions, increment exemplarCount")
    }

    companion object { const val MERGE_THRESHOLD = 0.75f }
}
```

### 3.6 Orchestrator — mirrors `MontyBase`'s step loop

```kotlin
class MontyOrchestrator(
    private val sensorModule: SensorModule,
    private val tier1: PrimitiveLM,
    private val tier2: CharacterGraphLM
) {
    fun stepEpisode(observations: List<RawTouchObservation>) {
        tier1.preEpisode(); tier2.preEpisode()

        for (obs in observations) {
            val smMessage = sensorModule.step(obs)
            tier1.matchingStep(listOf(smMessage))
            val t1Vote = tier1.sendOutVote()
            tier2.matchingStep(listOf(t1Vote))
            tier2.receiveVotes(emptyList())   // Phase 8: populated by sibling glide LMs
        }

        tier1.postEpisode(); tier2.postEpisode()
    }
}
```

---

## 4. Mapping table: faithful port vs. deliberate simplification

| Monty concept | This app | Faithful or simplified? |
|---|---|---|
| CMP `Message` fields | `CmpMessage` — same fields | **Faithful** |
| 3×3 pose vectors, 3D location | 2×2 pose vectors, 2D location | **Simplified** — 2D domain |
| `SensorModule` reading a simulated/real sensor | `SensorModule` reading resampled touch points | **Faithful interface**, different observation source |
| `matching_step` / `receive_votes` | `matchingStep` / `receiveVotes` | **Faithful** |
| `state_dict()`/load for persistence | `stateDict()`/`loadStateDict()` → JSON | **Faithful contract**, simple backing store |
| `GraphObjectModel` / `GraphMemory` | Same names, 2D fields | **Faithful** |
| `detect_new_object_k_steps` merge/spawn logic | `detectNewObject()` | **Faithful concept**, simplified scoring function |
| Motor system + simulator driving a sensor | None — touch input replaces the motor system | **Not ported** — the human *is* the motor system |
| Multiple LMs voting via lateral CMP | Single LM per tier in v1; multiple `PrimitiveLM`s voting in Phase 8 | **Deferred**, same interface supports it |
| Goal-State Generators / `CmpGoal` | Present as a class shape, unused until Phase 8 | **Stubbed** |

Be honest with yourself about the last two rows while building v1: most of
the voting/goal machinery is architecturally present but does nothing yet,
because a single touch-drawn character doesn't need it. The payoff is that
Phase 8 becomes "add LM instances and populate `receiveVotes`," not a
redesign.

---

## 5. Phased Build Plan

### Phase 0 — Project Setup (½–1 day)
New Android Studio project with **two Gradle modules from day one**: `lib`
(`kotlin("jvm")` plugin, zero Android dependencies) and `app`
(`com.android.application`, depends on `lib`) — see §2a. Set up Jetpack
Compose in `app`. `Canvas` composable in `app` capturing `MotionEvent` and
rendering strokes live. No recognition logic yet.
**Exit:** draw a character, see it rendered, dump raw stroke points to
Logcat; `./gradlew :lib:test` runs (even with zero tests) to confirm the
module boundary compiles with no Android SDK on its classpath.

### Phase 1 — SensorModule: Capture & Resampling (1–2 days)
Buffer strokes by pointer down/up. Implement arc-length resampling to N
evenly-spaced points (N ≈ 32–64). Normalize (translate to centroid, scale to
reference size). Implement `SensorModule.step()` to emit `CmpMessage`s.
**Exit:** same letter fast/slow, small/large → near-identical point sequences
after resample+normalize.

### Phase 2 — `PrimitiveLM` (Tier-1) (2–4 days)
Implement `matchingStep()`: compute local tangent angle per point, segment
into line/arc/corner runs by tangent stability. `sendOutVote()` emits the
primitive as a `CmpMessage` with relative (not absolute-screen) tangent angle.
**Exit:** unit tests on synthetic point arrays — a straight line, an "L", a
"C" — produce the expected primitive sequences.

### Phase 3 — `CharacterGraphLM` (Tier-2) + `GraphMemory` (3–5 days)
Build `GraphObjectModel` from a character's primitives (nodes = primitives
w/ relative pose to centroid; edges = relations incl. cross-stroke). Implement
`matchScore()` — order/direction-tolerant point-set matching. Implement
live evidence accumulation as strokes complete, not just at the end.
**Exit:** teach 2–3 labels (hardcoded via debug button), draw a fresh instance,
correct label scores highest.

### Phase 4 — Teach & Recognize UI Loop (2–3 days)
Teach flow (no match → prompt for label → `addOrMerge`), recognize flow (live
evidence bars → top result + confirm/correct), correction flow (wrong guess →
becomes a teaching example), tie/ambiguity flow (close scores → disambiguation
question, not a forced guess).
**Exit:** full loop works for a handful of self-taught labels, including a
deliberately ambiguous pair.

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
`stateDict()`/`loadStateDict()` → JSON on app start/stop. Debug/inspector
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
  Robolectric):**
  - Resampling, normalization, and primitive segmentation — pure functions
    on point arrays (perfect square, perfect circle, straight line at
    various angles).
  - `GraphMemory.matchScore` / `detectNewObject` merge-vs-spawn decisions
    against hand-built `GraphObjectModel` fixtures.
  - `MontyOrchestrator` step-loop behavior driven entirely by synthetic
    `RawTouchObservation` sequences — no real touchscreen or device needed.
  - `stateDict()`/`loadStateDict()` round-trips on plain Kotlin data.
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
- **Multi-stroke composition remains the least theoretically settled part**
  of TBP itself (flagged as immature even in Monty) — the variant-based
  fallback is a pragmatic substitute for genuine compositional
  generalization, not a solved version of it.
- **True symmetric ambiguities (6/9, O/0) are expected, permanent outcomes**,
  not defects — the app's job is to surface them cleanly, not eliminate them.
- **Most of the CMP/voting/goal machinery is dormant until Phase 8** — don't
  mistake unused interface surface for wasted effort; it's what makes Phase 8
  additive instead of a rewrite.
