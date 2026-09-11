# Implementation Plan — TBP-Inspired Handwriting Recognition App

**Platform:** Android (Kotlin) · **Status:** Phases 0–5 and 5.5a done, plus
Phase 4.5 — none of which §5 originally planned for. **Phase 4.5**, **Phase
5**, and **Phase 5.5a** below were inserted after the fact and this section
was renumbered to match; see the note at the head of §5 for why. **Phase
5.5b (Merge/Spawn Tuning), the work originally planned as "Phase 5," has
*not* been done** — it's blocked on real handwriting data Phase 5.5a's
capture tool can now produce, but hasn't yet (see below).

Tier 1 was **redesigned** in what's now **Phase 4.5** (see §7's final
entries): `PrimitiveSensorModule`, a hand-coded line/arc geometric
classifier, was recalibrated five times chasing real hand-drawn failures
and never converged — replaced with `PrimitiveGraphLM`, a taught,
evidence-matched `LearningModule` (the app learns primitive shapes the way
it learns characters, no fixed shape vocabulary), plus `StrokeSegmenter`, a
global dynamic-programming segmentation search over a whole stroke,
replacing the old per-point local threshold decision. `lib`'s brain
pipeline (sensor, both LM tiers, orchestrator) is rebuilt and tested end to
end against the concrete real-drawing failures that motivated it. **Phase
5** (the teach-primitives UI plus the background-thread matching fix, §7's
latest entries) is now built: `app` has a primitives/characters mode
switch, with character-teaching gated behind having taught at least one
primitive, and all `lib` calls run off the main thread — the app is
end-to-end functional for a real user for the first time.

**Phase 5.5a** (stroke capture/export tool) is also now built: `app` can
record taught examples (opt-in, off by default) and export them as JSON via
the system share sheet — built specifically because Claude cannot drive a
connected device (`CLAUDE.md`) and there was no other way to get real
handwriting data into a `lib` test.

**What's actually next is Phase 5.5b**, not new work: real-handwriting
merge/spawn and segmentation-constant tuning (`GraphMemory.MERGE_THRESHOLD`
is still the untouched `0.75f` placeholder from Phase 3) against exported
captures — this is what §5 originally called "Phase 5," before Phase
4.5/5/5.5a above got inserted ahead of it. It needs the user to actually
draw on-device with capture on and hand back an export; nothing has been
tuned yet. No on-device verification has been done for any of Phases 4–5.5a
(standing project rule, see `CLAUDE.md`) — that, too, is still the user's
to confirm.
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
| Architecture | 2-tier `LearningModule` hierarchy, Monty-faithful interfaces | Tier 1: primitives (line/arc). Tier 2: character graphs |
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
│ PrimitiveSensorModule : SensorModule<CmpMessage>               │
│  step(msg) → CmpMessage — segments into line/arc runs (whole-  │
│                window geometric fit — see §3.4, no separate    │
│                corner type: a bend is just two adjacent runs); │
│                a SensorModule, not a LearningModule — see      │
│                §3.4's "Known asymmetry" note for why            │
└───────────────┬────────────────────────────────────────────────┘
                │
┌───────────────▼────────────────────────────────────────────────┐
│ Tier-1 (the only LM tier): CharacterGraphLM : LearningModule<...> │
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
| **`lib`** | `kotlin("jvm")` — a plain Kotlin/JVM module, **not** `com.android.library` | Every "brain" class: `CmpMessage`/`CmpGoal`, the `SensorModule`/`LearningModule` interfaces, `PrimitiveSensorModule`, `CharacterGraphLM`, `GraphObjectModel`/`GraphMemory`/`GraphMatcher`, `MontyOrchestrator` | **No.** The Android SDK is not even on `lib`'s compile classpath, so an accidental `import android.*` is a compile error, not a lint warning. |
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
`TouchSensorModule`), `PrimitiveFeatures` (`measurement`/`startIndex`/
`endIndex`/`strokeIndex`, from `PrimitiveSensorModule` — `measurement`'s own
Line/Arc variant doubles as the primitive's type, no separate enum) — and a consumer does one typed
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
generic `State` type param — each LM's state shape is its own business.
`CharacterGraphLM`'s is `Map<String, List<GraphObjectModel>>`, its actual
learned memory. It's currently the only `LearningModule` in this app's
hierarchy (see §3.4's "Known asymmetry" note) — `PrimitiveSensorModule` is
a `SensorModule`, which has no `state()`/`loadState()` contract at all,
matching Monty's own SMs not needing to persist learned state either.

### 3.4 Tier implementations

> **Superseded** — `PrimitiveSensorModule` (the code sketch below) was
> deleted and replaced by `PrimitiveGraphLM` + `StrokeSegmenter` after five
> recalibrations of this exact design never converged on real handwriting.
> See §7's final entry for the full story and the new design. This section
> is kept as history (the "Known asymmetry"/SM-vs-LM reasoning below still
> explains *why* Tier 1 used to be shaped this way) rather than rewritten,
> since the actual code (with current KDoc) is the source of truth now.

```kotlin
class PrimitiveSensorModule(override val sensorId: String) : SensorModule<CmpMessage> {
    // A SensorModule, not a LearningModule -- see this section's "Known
    // asymmetry" note for why that's the Monty-faithful choice, not a
    // shortcut. That also means no receiveVotes/sendOutVote/state/
    // loadState/setExperimentMode: none of those exist on SensorModule,
    // because Monty's own SMs don't vote, don't persist learned state, and
    // don't have training-vs-eval behavior either -- this class never used
    // any of them for real (they were permanent no-ops under the old
    // LearningModule framing).
    //
    // step() buffers a candidate run of points as long as they, taken AS A
    // WHOLE, still fit one of two idealized shapes, each its own tolerance:
    //   - line: every point within LINE_WIDTH_TOLERANCE of the best-fit
    //     line through them (PCA major axis) -- "fits inside a thin
    //     rectangle"
    //   - arc: every point within the tighter ARC_WIDTH_TOLERANCE of a
    //     best-fit circle's circumference (least-squares algebraic fit) --
    //     "fits inside a thin donut"
    // A short window (few points) can trivially find some large-radius
    // circle passing within a loose tolerance of a moderate corner's two
    // legs -- ARC_WIDTH_TOLERANCE being tighter than LINE_WIDTH_TOLERANCE is
    // what catches that now, replacing an earlier per-point curvature veto
    // (MAX_LOCAL_TURN) that compared curvature in the character's own
    // shared normalized space and so couldn't tell "a small feature next to
    // a much bigger one" from "a genuine corner" -- see
    // PrimitiveSensorModule.kt's class doc for the full reasoning (and §7
    // for the dead-zone/threshold bugs that drove the earlier rewrites).
    //
    // There is no third CORNER type. A sharp bend is just the boundary
    // between two LINE/ARC runs — the angle between two adjacent nodes'
    // own recorded poses already carries how sharp it was, so a dedicated
    // corner node added no information a LINE-LINE pair didn't already
    // have, while making a hand-drawn corner's graph depend on whether it
    // was drawn as one stroke or split across two (see
    // PrimitiveSensorModule.kt's class doc for the full reasoning).
    //
    // A pen lift between strokes of the same character is *not* a separate
    // episode (see §3.6) — it's a strokeIndex discontinuity inside this
    // same message stream, and step() force-breaks the open run when it
    // sees one, the same way Monty signals sensor discontinuities
    // (on/off-object) as a per-message flag (`use_state`) rather than a
    // distinct lifecycle scope.
    //
    // The finished run is measured, not just classified: a line's chord
    // length (first point to last), or an arc's signed accumulated sweep
    // angle plus radius, both read off the same circle fit already used to
    // decide it was an arc -- see §7's entries on PrimitiveMeasurement for
    // why this exists (a bare type tag carried no size information at all)
    // and why sweep angle is a running sum of per-step deltas rather than
    // one first-to-last subtraction (correctness for a near-full-loop run).
    // PrimitiveMeasurement's own Line/Arc variants double as the primitive's
    // type now -- there's no separate enum (see §7).
    //
    // step() must return exactly one CmpMessage per call (SensorModule's
    // contract), but a line/arc run can span many observations before it's
    // complete -- most steps return passMessage = false ("nothing new"),
    // and one run may still be open when the episode ends. postEpisode()
    // can't return a value to carry that trailing primitive through the
    // normal path, so drainTrailingPrimitive() is this class's one
    // deliberate, documented addition beyond the plain interface.
    override fun step(observation: CmpMessage): CmpMessage { TODO("decode + fit-test; a strokeIndex change force-breaks the open run") }
    override fun preEpisode() { /* reset run-tracking state for the new character */ }
    override fun postEpisode() { /* flush whatever run is still open, for drainTrailingPrimitive() to retrieve */ }
    fun drainTrailingPrimitive(): CmpMessage? = TODO("consume-once trailing primitive from postEpisode()")
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

**Known asymmetry — resolved by matching Monty's actual layering, not by
"fixing" Tier 1 to learn.** Only `CharacterGraphLM` has real learned memory
(`GraphMemory`, evidence accumulation, merge/spawn); `PrimitiveSensorModule`
classifies with fixed geometric fit tests (distance from a best-fit line
or circle, within one shared tolerance — see §3.4's sketch and
`PrimitiveSensorModule.kt`'s class doc). This asymmetry was originally
logged here as a simplification "not yet fixed," implying the primitive
tier owed the system a taught graph memory the way `CharacterGraphLM` has
one. Checking real Monty's own source (`sensor_modules.py`) said otherwise:
Monty's own `SensorModule` computes curvature via a fixed, deterministic
least-squares surface fit — not a learned model either — and a single-SM/
single-LM hierarchy (exactly this app's shape, with `CharacterGraphLM` as
the *only* `LearningModule`) is Monty's own ordinary baseline
configuration, not a stripped-down special case. So the primitive tier's
job (turn a raw point stream into typed local features with pose) is
architecturally SM-shaped work, not LM-shaped work that got shortcut —
this was first just *documented* (`PrimitiveLM` still implemented
`LearningModule<Unit>`, with `receiveVotes`/`sendOutVote`/`state`/
`loadState`/`setExperimentMode` all permanent no-ops), then actually acted
on: `PrimitiveLM` was rewritten as `PrimitiveSensorModule`, a real
`SensorModule<CmpMessage>` chained after `TouchSensorModule`, shedding
every one of those vestigial methods because `SensorModule` doesn't have
them. Primitives (line/arc) stay a small, closed, geometrically-definable
vocabulary, unlike open-ended taught characters — nothing to fix here per
se. Revisit only if primitive-level ambiguity (a segment that's genuinely
borderline line/arc, or a tight arc's curvature reading close to the
sharp-kink veto — see §7) turns out to matter once real handwriting is
tested against it (Phase 5.5b) — and see §7 for the deeper "should boundary
detection itself be a learned, evidence-based process" question this was
weighed against, which real Monty hasn't made robust either.

### 3.5 Graph memory — mirrors `GraphObjectModel` / `GraphMemory`

```kotlin
data class GraphNode(
    val id: Int,
    val location: FloatArray,              // relative to the character's own centroid
    val absoluteAngle: Float,              // tangent angle in the character's own drawing-order frame
    val measurement: PrimitiveMeasurement  // type (as the Line/Arc variant) + size — see §3.1's note; no separate type enum
)

// sealed interface PrimitiveMeasurement { data class Line(length); data class Arc(sweepAngle, radius) } — see §3.4's PrimitiveSensorModule and §7

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

The per-node score is now a three-way average — angle, position, and
`measurement` — not two: a line's `length` or an arc's `sweepAngle` is
scored to 0..1 against `MAX_LENGTH_ERROR`/a full turn *independently per
node* before averaging across the window, since the two variants live on
different scales and can't share one error normalization (see §7's own
entry on this). Without this term, a short line and a long line pointing
the same direction were literally indistinguishable to `GraphMatcher` —
only orientation and position carried any signal.

### 3.6 Orchestrator — mirrors `MontyBase`'s step loop

Built in Phase 4, wiring `app`'s touch input to the pipeline Phases 0–3
validated by driving each tier directly from unit tests (see
`CharacterGraphLMTest`'s `drive()` helper, which is essentially this loop
already, minus a real class around it).

**Episode boundary is uniform across every tier — not per-LM-type.** Real
Monty's `MontyExperiment.run_episode()` calls `MontyBase.reset()` exactly
once per episode, and `reset()` loops over *every* sensor module and
learning module identically (`monty_base.py`'s `reset()`) — nothing in
Monty lets one LM define a different episode scope than another; the
experiment/environment owns that boundary, not the LM. Since one Monty
episode is one full object presentation (potentially many sensor movement
steps before `is_done`), this app's matching unit is one full
**character** — potentially several strokes — not one stroke. So
`preEpisode()`/`postEpisode()` on both `PrimitiveSensorModule` and
`CharacterGraphLM` bound one character, together, always. (An earlier
draft of this section briefly proposed a stroke-scoped episode for the
primitive tier and a character-scoped one for `CharacterGraphLM` — worth
recording as a mistake
caught by checking Monty's actual source rather than reasoning about it
from the class names alone, same category of correction as §3.3's
`getOutput`/`sendOutVote` split.)

The pen lift between strokes of the same character is therefore *not* a
second, smaller episode — it's an ordinary discontinuity riding in the
message stream, the same way Monty itself signals a sensor discontinuity
(leaving an object, `on_object` going false) via a per-observation flag
(`use_state`, `sensor_modules.py`) rather than a separate lifecycle scope.
Here, `StrokeFeatures.strokeIndex` already carries exactly this:
`PrimitiveSensorModule.step()` force-breaks its open run whenever
`strokeIndex` changes, with no new message field needed.

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
    private val primitiveSensor: PrimitiveSensorModule,
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
     * Recomputes the whole character from scratch. primitiveSensor's
     * preEpisode()/postEpisode() fire on every replay — harmless, since
     * it's fully stateless across calls — so evidence stays live as
     * strokes are added or removed. tier2.postEpisode() deliberately does
     * NOT fire here: its only consequential effect (snapshotting for
     * teach()) is reserved for the true end of the character, in
     * endCharacter().
     */
    private fun replay() {
        primitiveSensor.preEpisode()
        tier2.preEpisode()
        for (observations in buildNormalizedObservations()) {
            for (observation in observations) {
                val touchMessage = sensorModule.step(observation)
                val primitiveMessage = primitiveSensor.step(touchMessage)
                tier2.matchingStep(listOf(primitiveMessage))
                tier2.receiveVotes(emptyList()) // Phase 8: populated by sibling glide LMs
            }
        }
        primitiveSensor.postEpisode()
        primitiveSensor.drainTrailingPrimitive()?.let { tier2.matchingStep(listOf(it)) }
    }
    // Each passMessage=true primitive message is also recorded as a
    // PrimitiveOverlay (measurement + on-screen bounding box) for a debug
    // canvas overlay, alongside the tier2.matchingStep() call above — see
    // §7's entry on this for why the bounding box is exact rather than an
    // inverse-normalization approximation: buildNormalizedObservations()
    // below stashes each stroke's resampled points from *right before*
    // normalization, and a primitive's PrimitiveFeatures.startIndex/
    // endIndex/strokeIndex slice directly into that same array (same index
    // order), so the box is just a min/max over the sliced raw-pixel points.

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
length — worth revisiting in Phase 5.5b if very unevenly-sized strokes (e.g.
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
| SM does fixed feature extraction (e.g. curvature via least-squares fit); LM builds/matches learned graph memory | `TouchSensorModule`→`PrimitiveSensorModule` chained as two `SensorModule`s doing fixed feature extraction/segmentation (line/arc fit tests); `CharacterGraphLM` is the only `LearningModule` | **Faithful** — a single-SM-chain/single-LM hierarchy is Monty's own ordinary baseline configuration (see §3.4's "Known asymmetry" note) |
| Motor system + simulator driving a sensor | None — touch input replaces the motor system | **Not ported** — the human *is* the motor system |
| Multiple LMs voting via lateral CMP | Single LM in v1; multiple `CharacterGraphLM`s voting in Phase 8 | **Deferred**, same interface supports it |
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

> **Renumbering note.** Phases 4.5, 5, and 5.5a below were not in the
> original plan. After Phase 4 shipped, real hand-drawn testing forced a
> full Tier-1 rewrite (the geometric classifier never converged — see §7)
> before the originally-planned "Phase 5" (merge/spawn tuning) could even
> be meaningfully attempted, and that rewrite left Tier 1 with no way to
> teach primitives at all, which needed its own UI phase before anything
> could be tested end to end. Once both of those shipped, attempting the
> merge/spawn tuning itself ran into a second, unplanned blocker: Claude
> cannot drive a connected device (`CLAUDE.md`), and `app` had no way to
> export a drawn stroke to a file, so there was no way to get real
> handwriting into a `lib` test at all — **Phase 5.5a** (a stroke
> capture/export tool) was inserted to fix that. The original Phase 5
> (merge/spawn tuning) is renumbered **Phase 5.5b** and is the actual next
> phase — it has not been started, and needs the user to actually use the
> new capture tool on-device and hand back an export before it can be.

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

### Phase 4 — Teach & Recognize UI Loop (2–3 days) ✅ Done
Build `MontyOrchestrator` for real (§3.6) and wire `app`'s touch input to
it. The core design point (§3.6): one episode is one full **character**,
uniform across both tiers — matching Monty's own `MontyBase.reset()`, which
applies identically to every SM/LM regardless of hierarchy position.
Strokes are steps *within* that episode, not episodes of their own; a pen
lift is a `strokeIndex` discontinuity the primitive tier reacts to
directly, mirroring how Monty signals sensor discontinuities (`use_state`)
as message data rather than a separate lifecycle call.

**`lib` additions:**
- `MontyOrchestrator` (§3.6): `beginCharacter()` / `stepStroke(points)` /
  `undoLastStroke()` / `clearCharacter()` / `endCharacter(): RecognitionResult`
  / `teach(label)`. Normalizes once across every stroke drawn so far in the
  character (not per stroke — see §3.6's note on why), replaying the whole
  pipeline from scratch on every change; this also means `undoLastStroke()`/
  `clearCharacter()` live here, not as `app`-side replay logic.
- The primitive tier's `step()`: detect a `strokeIndex` change against the
  buffered run and force a break, so a run never silently spans a pen
  lift. Update its `preEpisode`/`postEpisode` doc comments — they now
  bound one character, not one stroke. (This tier was later rewritten as
  `PrimitiveSensorModule`, a real `SensorModule` — see §3.4/§7 — but the
  `strokeIndex`-discontinuity behavior itself is unchanged.)
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
- Tests: a case for a stroke gap not bleeding into a run;
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
surfaces a disambiguation prompt rather than a forced guess. **Implemented
and covered by `MontyOrchestratorTest`; the on-device confirmation itself
has not been performed** (standing project rule — no `adb` testing against
a connected device, see `CLAUDE.md` — this is the user's to do).
Superseded in part by Phase 4.5 below: the `PrimitiveSensorModule` this
phase built on top of was later deleted and replaced.

### Phase 4.5 — Tier-1 Redesign: `PrimitiveGraphLM` + `StrokeSegmenter` ✅ Done
*(Not in the original plan — inserted after real hand-drawn testing showed
Phase 2's fixed-threshold geometric classifier was a structurally wrong
approach, not a tuning problem. Full blow-by-blow in §7's "Threshold
tuning" entry; this is the short version.)*

Five successive recalibrations of `PrimitiveSensorModule` (the Phase 2
line/arc classifier) each fixed the specific real-drawing shape that broke
it while leaving the next shape broken, because forcing every window of a
continuously-varying hand-drawn stroke into a small, fixed, hand-designed
shape vocabulary via *any* geometric distance threshold is inherently
lossy — confirmed by checking real Monty's own source, which has no
analogous step: its SM emits dense per-point features and its LM matches
them via evidence accumulation against learned templates, never rounding a
continuous shape into a hand-designed taxonomy first.

**The redesign**, reusing existing infrastructure rather than inventing a
parallel system:
- **`PrimitiveGraphLM`** — a genuine taught `LearningModule` for Tier 1,
  structurally parallel to `CharacterGraphLM`: primitive shapes are taught
  by drawing + labeling an example, exactly like teaching a character, and
  matched via the same order/direction-tolerant alignment search
  `GraphMatcher` already used, extracted into a shared `AlignmentSearch`/
  `AlignmentScore` utility (share the *algorithm*, not the *data shape* —
  see §7 for why `PrimitiveGraphLM` and `CharacterGraphLM` deliberately
  stayed two classes rather than one generic `EvidenceGraphLM`, despite
  real Monty using exactly one class at every hierarchy level).
- **`StrokeSegmenter`** — a Viterbi-style dynamic-programming segmentation
  replacing the old per-point local threshold decision with a *global* one:
  finds the partition of a whole stroke into windows maximizing total
  evidence (log-evidence, so it doesn't always prefer more, shorter
  primitives — one of three real bugs found and fixed empirically, see §7).
- `PrimitiveMeasurement` generalized from a closed `Line`/`Arc` sealed type
  to one open `data class(label, extent)` — primitives are now arbitrary
  taught labels, not a fixed two-variant enum.
- `PrimitiveSensorModule` (the class) was deleted, then later reinstated as
  a different class of the same name — a thin `SensorModule` wrapper around
  `PrimitiveGraphLM`/`StrokeSegmenter` — once it became clear
  `MontyOrchestrator` had absorbed that class's responsibilities by
  default rather than by design; see §7's final entry.

**Exit:** verified against all three concrete real-drawing failures that
motivated the redesign (a small hook next to a long tail, a single
gently-curving stroke, an S-curve), each now a permanent regression test in
`MontyOrchestratorTest`; all `lib` tests pass. `app`'s teach UI still only
knew how to teach characters at this point — nothing could be recognized
end to end through the real app yet (closed by Phase 5, below).

### Phase 5 — Teach-Primitives UI + Background Matching ✅ Done
*(Also not in the original plan — a direct consequence of Phase 4.5: the
Tier-1 redesign left no way to teach the primitive templates
`PrimitiveGraphLM` now depends on, so nothing could be recognized end to
end through the real app until this shipped. Full detail in §7's last three
entries.)*

- `RecognizerViewModel` gained a `TeachMode` (`PRIMITIVES`/`CHARACTERS`,
  defaulting to `PRIMITIVES`) reading/writing `PrimitiveGraphLM` directly
  (not through `MontyOrchestrator`, whose reason to exist — coordinating
  segmentation during a character episode — doesn't apply to teaching one
  primitive in isolation). A primitive is one completed stroke, a `lib`
  constraint (`PrimitiveGraphLM.resample()` has no stroke-boundary
  concept), not a UI convenience.
- Character-teaching is gated behind having taught at least one primitive
  — otherwise every primitive silently degrades to `"unknown"` and a
  character taught on top of that permanently poisons `GraphMemory`.
- Primitive teaching got the same recognize/confirm/correct/disambiguate
  flow characters already had (`PrimitiveTeachingPanel`), reusing
  `possibleMatches()`/`recognitionResult()` — pulled out of
  `CharacterGraphLM.kt` into a standalone `RecognitionResult.kt` since
  neither function was ever character-specific.
- **Follow-up fix, same phase:** all `lib` access from `RecognizerViewModel`
  was synchronous on the main thread, and visibly froze the UI as taught
  templates/characters accumulated. Fixed by confining all
  orchestrator/LM/memory access to one serial dispatcher
  (`Dispatchers.Default.limitedParallelism(1)`), with each method bundling
  its mutation and subsequent state read into one dispatch (never split
  across two) to avoid a data race between a queued mutation and a
  separately-dispatched read of the same mutable `lib` state.
- **A related structural fix, same phase:** `SensorModule<T>` had drifted
  to zero implementations after Phase 4.5 deleted the original
  `PrimitiveSensorModule` and never reinstated anything behind the
  interface — `MontyOrchestrator` had grown the resample/segment/emit
  logic inline instead. Reinstated a concrete `PrimitiveSensorModule :
  SensorModule<RawTouchObservation>` (a different class than the deleted
  one) wrapping `PrimitiveGraphLM`/`StrokeSegmenter`, matching how real
  Monty's `TwoDSensorModule` stays a concrete `SensorModule` even though its
  own feature extraction also needs more than one raw sample.

**Exit:** teach a handful of primitives, then teach a multi-primitive
character built from them, end to end through the real `app` UI for the
first time. Verified via `./gradlew :lib:test :lib:ktlintCheck
:app:ktlintCheck :app:compileDebugKotlin` (81 `lib` tests as of this
writing, all passing); **no on-device verification performed** (standing
project rule, see `CLAUDE.md`) — confirming the UI flow and that the
threading fix actually resolved the freeze is the user's to do.

### Phase 5.5a — Stroke Capture/Export Tool ✅ Done
*(Also not in the original plan. Blocked Phase 5.5b before it could even
start: tuning against "real handwriting samples" needs real handwriting
data in a `lib` test, and there was no way to get it there — no
serialization/export existed anywhere in `app`, and Claude cannot drive a
connected device to generate that data itself, per `CLAUDE.md`. Asked the
user how to source calibration data; they chose building this tool over a
synthetic-jitter-only pass or waiting for manually-transcribed bug
reports.)*

- `RecognizerViewModel` gained an opt-in, off-by-default `captureEnabled`
  toggle and a `capturedExamples: List<CapturedExample>` buffer
  (`app/.../capture/StrokeCapture.kt`). A private `captureIfEnabled()`
  records the exact strokes + label at the one point both are already in
  hand — the top of `onTeach`/`onTeachPrimitive` — so "Confirm" (which
  delegates to the same methods) is captured for free too, giving natural
  repeated-variation examples of the same label without extra wiring.
  Off by default deliberately: this records the user's own handwriting.
- Export (`app/.../capture/CaptureExporter.kt`) writes a JSON file
  (`{"schemaVersion": 1, "captures": [{"mode", "label", "timestampMs",
  "strokes": [[{"x","y"}, ...], ...]}, ...]}` — a direct mirror of
  `List<List<RawPoint>>`, the shape `MontyOrchestrator`/`PrimitiveGraphLM`
  already consume, so a future `lib`-side fixture loader needs no
  reshaping) to the app's cache dir and hands it to the system share sheet
  via a `FileProvider` (`androidx.core`, already a dependency — no new
  library) — the user picks where it goes (Drive/email/Files/...)
  themselves; nothing leaves the device through this app's own code, and
  no storage permission is needed.
- Uses `org.json` (already on the Android platform — zero new *production*
  dependency); a real `org.json:json` jar was added as a `testImplementation`-only
  dependency (`gradle/libs.versions.toml`) since Android's bundled `org.json`
  throws on the host JVM without Robolectric, which this project doesn't use.
- `DrawingScreen.kt` gained a small "Capture: on/off (N)" + "Export N
  examples" control in the same debug corner as the existing `LmState`
  toggle — plain `Button`/`OutlinedButton`, this app's established
  no-new-visual-idiom convention for small controls.
- New `app/src/test/kotlin/com/eta/tbp/app/capture/StrokeCaptureTest.kt` —
  the first test `app` has ever had (confirmed: no `app/src/test` directory
  existed before this) — a schema round-trip smoke test for
  `toCaptureJson()`, matching §6's "thin adapter" testing philosophy for
  `app` rather than exhaustive coverage.
- Verified via `./gradlew :lib:test :app:test :lib:ktlintCheck
  :app:ktlintCheck :app:assembleDebug` — `lib` untouched (81 tests still
  pass), `app`'s 2 new tests pass, both modules' ktlint clean, `app`
  assembles. **No on-device verification performed** (standing project
  rule) — using the toggle/export flow for real, and actually producing a
  calibration export, is the user's to do next; see Phase 5.5b below for
  what to draw.

### Phase 5.5b — Merge/Spawn Tuning (2–3 days) — *(originally "Phase 5";
renumbered — see the note at the head of §5)* **Not started, blocked**
Tune `GraphMemory.MERGE_THRESHOLD` (still the untouched `0.75f` placeholder
from Phase 3) and `matchScore` against real handwriting samples — this is
Monty's own `detect_new_object_*` mechanism, not a custom heuristic bolted
on afterward. This is the actual next phase: everything that shipped as
Phase 4.5/5/5.5a above was necessary supporting work (a working Tier-1 to
teach, a UI to teach it with, a way to export what gets drawn), not a
substitute for this tuning pass. Also revisit the segmentation constants
`StrokeSegmenter` introduced (`MIN_WINDOW_LENGTH`/`SEGMENT_PENALTY`),
validated so far only against the specific repro shapes in §7, not a broad
sweep of real handwriting.

**Blocked on**: the user turning on Phase 5.5a's capture toggle and
drawing — ideally the same label 3-4 times with natural variation, a
couple of clearly different labels, and one multi-stroke character drawn
with different stroke order at least once (directly targets this phase's
exit criteria below) — then exporting and handing back the resulting
`captures-*.json`. Once that exists: add it under
`lib/src/test/resources/realcaptures/`, add a small JSON-fixture loader to
`lib`'s test sources (a `testImplementation`-only `org.json:json`
dependency there too, same reasoning as Phase 5.5a's — `lib` has zero
Android SDK on its classpath even in tests), and add real-data test cases
to `GraphMemoryTest`/`MontyOrchestratorTest` at the actual merge/spawn
decision boundary (today's coverage is binary — near-1.0 or near-0.0 —
with nothing exercising ~0.75 under realistic variation).

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
2. Run one simulated "glide" instance per connected component/branch,
   advancing by straightest-continuation at junctions.
3. **Voting for disambiguation:** at an ambiguous junction, use relative-
   position consistency across simultaneously-running glide instances, plus
   top-down bias from `CharacterGraphLM`'s current leading hypothesis
   (populate `CmpGoal` + `receiveVotes` here — this is exactly what those
   stubs were for), instead of a purely local straightness heuristic. Since
   the primitive tier is now `PrimitiveSensorModule` (a `SensorModule`, not
   a `LearningModule`) rather than the original `PrimitiveLM`, and
   `receiveVotes`/`sendOutVote` only exist on `LearningModule`, this
   voting step would need to live at the `CharacterGraphLM` level (voting
   between sibling `CharacterGraphLM` instances over which glide's
   primitive sequence to trust) rather than between primitive-tier
   instances directly — worth resolving concretely when Phase 8 is
   actually picked up, not speculatively now.
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
  Robolectric) — 68 tests as of the `ARC_WIDTH_TOLERANCE`/`MAX_LOCAL_TURN`
  removal fix, across:**
  - `StrokePreprocessorTest` — resampling, normalization, tangent/curvature,
    the fast/slow + small/large invariance exit criterion, plus `smooth()`.
  - `TouchSensorModuleTest`, `CmpMessageTest` — message construction/accessors.
  - `PrimitiveSensorModuleTest` — line/arc segmentation across synthetic
    shapes and realistic jitter, the corner-angle sweep (no dedicated
    corner type — see §3.4/§7), the one-stroke-vs-two-strokes regression
    test for why that type was dropped, `PrimitiveMeasurement` cases
    (a line's chord length, a semicircle's/tight loop's sweep angle, that a
    longer leg reports a larger length than a shorter one, and that a
    shallow arc reports a larger circle radius than a semicircle sliced
    from the same normalized size), a gentle S-curve sharing one stroke's
    resample budget staying cohesive rather than fragmenting, and a small
    tight hook sharing a stroke with a much longer tail segmenting as
    exactly `[ARC, LINE]` (the two regression tests for the real-drawing
    curvature/veto bugs in §7).
  - `GraphMatcherTest` — order/direction tolerance, mismatched-kind/count
    rejection, partial-match prefix tracking, and that a `measurement`
    mismatch alone (same positions/angles, different line length or arc
    radius) scores below an exact match — on hand-built synthetic graphs.
  - `GraphMemoryTest` — merge-vs-spawn, snapshot/restore round-trips.
  - `CharacterGraphLMTest` — the actual Phase 3 exit criterion end to end
    through the full pipeline, `possibleMatches()`/`recognitionResult()`
    cases, plus a `getOutput()`/`evidenceSnapshot()` coherence check.
  - `MontyOrchestratorTest` — step-loop behavior driven by synthetic
    multi-stroke `RawPoint` sequences, including a pen lift mid-character,
    undo/clear, cross-stroke position discrimination,
    `currentPrimitiveOverlays()` reporting bounding boxes in raw touch
    coordinates (not normalized space) with one entry per primitive, and a
    taught corner shape not spuriously matching an unrelated arc (the
    orchestrator-specific half of the curvature-scale bug in §7) — no real
    touchscreen or device needed.
  - `state()`/`loadState()` round-trips on plain Kotlin data — only
    `CharacterGraphLM` has this contract now; `PrimitiveSensorModule` is a
    `SensorModule` and doesn't persist state at all (see §3.4).
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

  A follow-up real-usage pass, after tolerances were hand-tuned once,
  turned up a second structural gap in the same area: `decide()`'s
  line-consistency check averaged the tangent over the *whole* run, and
  since tangent is curvature's running integral, that average's
  sensitivity to a real, shallow, sustained curve keeps shrinking the
  longer the run has already gone on — letting a curvature well below
  `MIN_ARC_CURVATURE` eventually drift far enough to break the LINE
  hypothesis with nothing to hand off to (ARC's own floor was never
  reached), producing a spurious LINE/LINE split with no CORNER or ARC in
  between. Fixed by averaging over a bounded recent window instead
  (`LINE_TREND_WINDOW`), sized *from* `LINE_ANGLE_TOLERANCE`/
  `MIN_ARC_CURVATURE` rather than hand-picked, so retuning either
  threshold can't silently reopen the gap. Separately, hand-tuned
  threshold values turned out to be calibrated against the shape's raw
  geometric angle rather than measured post-resample/-smooth curvature,
  which is a very different scale (a synthetic single-point 90° corner
  measures only ~58° of curvature at `DEFAULT_RESAMPLE_COUNT`; a drawn
  semicircle measures only ~7-8°) — corrected against the actually-measured
  values (see `PrimitiveLM.kt`'s companion object comments).

  A third pass found the same "measured, not geometric" lesson applies
  within corner detection itself: `CORNER_CURVATURE_THRESHOLD` calibrated
  against a 90° right-angle corner's ~58°-66° measured peak (comfortably
  caught) turned out to miss any real corner shallower than roughly a
  120° interior angle — very common in ordinary handwriting, not an edge
  case — since measured peak curvature falls off fast as the bend gets
  gentler (a 150° interior angle measures only ~20°). A too-high threshold
  produced the same symptom as the dead-zone bug above: a real, discrete
  bend absorbed into neither segment, surfacing as a spurious LINE/LINE
  split. Lowered from ~40° to ~15°, based on a full sweep of measured
  curvature across interior angles from 90° to 170° (see
  `PrimitiveLMTest`'s corner-angle sweep) — comfortably above measured
  arc-range curvature (~8°) while catching corners down to ~150° interior.

  A fourth pass replaced the whole approach rather than tuning it further,
  triggered by a correctness bug the threshold recalibration couldn't fix:
  a corner drawn as one continuous stroke produced `LINE-CORNER-LINE` (3
  nodes), but the *identical* shape drawn as two strokes meeting at the
  same vertex produced `LINE-LINE` (2 nodes) — since a pen lift just
  force-breaks the open run without ever deciding whether a corner belongs
  there. `GraphMatcher.matchScore()` requires equal node counts, so these
  could never match each other, no matter how good the scoring math was.
  The fix considered and rejected first: synthesize a corner at stroke
  boundaries too, when the two strokes' endpoints are close enough to
  count as meeting. Rejected because it's fundamentally order/direction-
  dependent — strokes can be drawn in any order, from either end, with
  unrelated strokes interleaved between them, and an online per-point
  segmenter can't retroactively determine which stroke's endpoint connects
  to which other stroke's without a completely different (all-pairs,
  whole-character) analysis. The actual fix: **drop `CORNER` as a
  primitive type entirely.** The angle between two adjacent `LINE`/`ARC`
  nodes' own recorded poses already carries exactly how sharp a bend was
  — `GraphMatcher` already re-baselines and compares consecutive nodes'
  angles directly — so a dedicated corner node was never adding
  information a `LINE`-`LINE` pair didn't already have; it was just
  representing the same bend differently depending on drawing mechanics.
  With no corner type, a same-stroke bend and a cross-stroke bend (any
  order, any direction) produce the *same* sequence, because nothing is
  left that only fires for one of them. This also replaced the
  segmentation test itself, since simply removing `CORNER` from a per-point
  incremental threshold state machine wasn't enough on its own: `PrimitiveLM`
  now grows a candidate run only while its points, taken as a **whole**,
  fit a line (aspect ratio of the point cloud's principal-axis spreads —
  a PCA/total-least-squares collinearity test) or an arc (curvature's mean
  is non-trivial, its standard deviation small relative to that mean),
  with one shared per-point veto (a single sharp kink is discarded outright,
  joining neither run, rather than anchoring a degenerate one-point run of
  its own). This eliminates the per-point running-average machinery
  (`LINE_TREND_WINDOW`, `CORNER_CURVATURE_THRESHOLD`) that drove the three
  passes above — see `PrimitiveLM.kt`'s class doc for the full design and
  `PrimitiveSensorModuleTest`'s one-stroke-vs-two-strokes test for the
  regression check.

  **Known open gap from this pass (resolved by the sixth pass below):** a
  tight, small full loop (e.g. a complete circle drawn as one stroke,
  roughly what an "O" needs) can measure per-point curvature close to the
  same sharp-kink veto that's needed to catch a ~150°-interior corner — at
  `DEFAULT_RESAMPLE_COUNT`, a full 360° loop's curvature (~15°) and a
  fairly gentle corner's (~15-20° at 150-155° interior) are numerically
  close, so there's little room to set one veto that cleanly separates
  "genuinely sharp bend" from "tight but smooth loop." A quick check found
  a full circle at default settings under-segmented into a couple of
  `LINE`s rather than one clean `ARC`.

  A fifth pass acted on a question raised (not by tuning, but by
  re-examining the architecture) after the fourth pass: if a hand-drawn
  corner's sharpness is already fully recoverable from two adjacent
  `LINE`/`ARC` nodes' own recorded angles, and this tier's whole job is
  fixed geometric fitting rather than anything actually *learned* — is
  packaging it as a `LearningModule` still the right call? Checking real
  Monty's `SensorModule` (`sensor_modules.py`) confirmed it computes
  curvature via the same kind of fixed, deterministic math (a least-
  squares surface fit), not a learned model — so this tier's fixed-fit
  design was never the odd one out; it was mis-labeled. `PrimitiveLM` was
  rewritten as `PrimitiveSensorModule`, a real `SensorModule<CmpMessage>`
  chained after `TouchSensorModule`, making `CharacterGraphLM` the sole
  `LearningModule` in this app's hierarchy — Monty's own common baseline
  shape. This wasn't just a rename: it shed `receiveVotes`, `sendOutVote`,
  `state`/`loadState`, and `setExperimentMode` entirely, since none of
  those exist on `SensorModule` and every one of them had been a permanent
  no-op here anyway. The one real wrinkle: `SensorModule.step()` must
  return exactly one `CmpMessage` per call (no `getOutput()`-style
  queueing), which only works because — verified, not assumed — a run
  never needs to emit more than one finished primitive per incoming point;
  and `postEpisode()` can't return a value for a still-open run at episode
  end, so `drainTrailingPrimitive()` is this class's one deliberate,
  documented addition beyond the plain `SensorModule` interface. See
  `PrimitiveSensorModule.kt`'s class doc for the full design.

  A sixth pass fixed the tight-loop gap the fourth pass had flagged and
  left open — not by re-tuning the sharp-kink veto again, but by fixing
  what was actually structurally wrong with the arc test itself: curvature
  (a resampled circle's turning angle per step) is inherently
  tightness-dependent, so a small tight loop and a genuine sharp corner can
  measure similar curvature at a fixed resample density no matter where the
  veto threshold sits — there's no single number that cleanly separates
  them, because they're not actually different along the axis curvature
  measures. The fix, prompted by asking whether arcs could be defined the
  same way lines now are (a shape a point cloud fits *inside*, within a
  shared tolerance, rather than a differently-scaled statistical test):
  fit a least-squares circle through the window (an algebraic Kåsa fit,
  computed in centered coordinates for stability — see
  `PrimitiveSensorModule.kt`'s `fitCircle()`), and require every point to
  lie within `WIDTH_TOLERANCE` of that circle's circumference — the same
  tolerance the line test now uses, since a rectangle and a "donut" band
  are the same idea (a max distance from an idealized curve) applied to a
  straight shape versus a round one. A residual-from-fit is tightness-
  independent: a clean loop of any radius sits close to *some* circle,
  fixing the gap directly rather than papering over it with a better-tuned
  veto. `MIN_ARC_MEAN_CURVATURE`/`ARC_CURVATURE_RELATIVE_TOLERANCE`/
  `MIN_LINE_ASPECT_RATIO` were all removed in favor of the one shared
  `WIDTH_TOLERANCE`.
  
  This did *not* eliminate the sharp-kink veto entirely, though an initial
  attempt tried to: a circle has only 3 degrees of freedom, so a *short*
  window can trivially find some large-radius circle passing within
  tolerance of a moderate corner's two legs, "absorbing" a real ~130°
  corner into one `ARC` instead of rejecting it — confirmed by the
  corner-angle sweep test regressing the moment the veto was dropped. The
  veto stayed, recalibrated (`MAX_LOCAL_TURN`, ~18°) into the comfortable
  gap between the tight loop's own curvature (~15°, must not trip it) and
  the shallowest corner still worth catching (~150° interior, ~20°
  measured) — narrower and more defensive than before, not a
  reintroduction of curvature as the primary classification signal.
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
  primitives with fixed geometric fit tests, not a learned graph model the
  way Tier 2 does. This turns out to be Monty-consistent rather than a
  shortcut (see §3.4's "Known asymmetry" note — Monty's own SM computes
  curvature via fixed math too), so it's not something to "fix" by making
  Tier 1 learn a graph the way Tier 2 does. A related, deeper question —
  should *segmentation itself* (not just classification) be a learned,
  evidence-based process, the way Monty's own hierarchical LMs let object-
  part boundaries emerge from a lower LM's evidence stream rather than a
  fixed rule? — was deliberately not pursued: real Monty hasn't made that
  robust for its own object/part composition either (flagged immature in
  its own published work, same as the multi-stroke composition point
  above), and it would trade one class of tunable threshold (fit tests)
  for another (an evidence-decay boundary threshold) rather than removing
  tunables altogether. Revisit if Phase 5.5b's real-handwriting tuning turns
  up genuine primitive-level ambiguity (a segment that's a borderline
  line/arc call, or the tight-loop-vs-corner gap noted above) that the
  current fit tests can't represent.
- **When a design decision here is ambiguous, check real Monty's source
  before inventing something new** (see `CLAUDE.md` for the local reference
  checkout) — this app should stay legible to anyone who already knows
  Monty. The `getOutput`/`sendOutVote` split, dropping `getFeatureByName`
  for typed payloads, correcting the orchestrator's episode boundary to be
  uniform across tiers instead of per-LM-type (§3.6), and reframing Tier
  1's fixed-threshold design as SM-shaped work rather than a shortcut
  (§3.4) all came from doing exactly this after an earlier assumption had
  drifted from Monty's actual behavior.
- **A bare `PrimitiveType` carried no size/shape information at all** — a
  short `LINE` and a long `LINE` pointing the same direction, or a tight
  `ARC` and a nearly-full-circle `ARC`, scored identically in `GraphMatcher`
  since only orientation and position were ever compared. Fixed by adding
  `PrimitiveMeasurement` (`Line(length)` / `Arc(sweepAngle)`) alongside
  `PrimitiveType`, computed in `PrimitiveSensorModule.finalizeRun()` by
  reusing the same circle fit already computed for classification (no
  second fit): a line's `length` is the chord between the run's first and
  last point (equivalent to arc length by definition, since every point
  already lies within `WIDTH_TOLERANCE` of that chord); an arc's
  `sweepAngle` is the *signed accumulated* per-step angular delta around
  the fitted circle's center — not a single first-to-last subtraction,
  which would fold a near-full-loop's sweep into a misleadingly short
  apparent angle at the wraparound. `GraphMatcher.alignmentScore` folds
  this in as a third averaged term (`sizeScore`), normalized per-variant
  (length against `MAX_LENGTH_ERROR`, sweep angle against a plain, un-
  wrapped full turn — a sweep angle is a total rotation amount, not a
  periodic heading, so `angleDifference`'s wraparound logic would wrongly
  treat two very different large sweeps as "close" near the 2π boundary).
  `GraphMemory.mergeInto` averages it the same way (plain weighted mean for
  both variants, not a circular mean, for the same reason).
- **Two follow-up corrections to the above, both from user review:**
  1. **An arc's `sweepAngle` alone still wasn't a complete size measure** —
     a tight quarter-turn and a huge, gently-curving quarter-turn report the
     same sweep angle and were still indistinguishable in size, the exact
     gap that motivated adding a line's `length` in the first place. Fixed
     by adding `radius` to `PrimitiveMeasurement.Arc` (already sitting in
     the circle fit, just previously discarded), and folding a radius-error
     term into `GraphMatcher`'s arc `sizeScore` (averaged with the
     sweep-angle term, both normalized against the same normalized-space
     scale a line's length uses).
  2. **`PrimitiveType` (an enum) and `PrimitiveMeasurement` (a sealed
     `Line`/`Arc` class) were two parallel discriminants for the same
     fact** — a `LINE`-typed node always had a `Line` measurement and vice
     versa, but nothing enforced that beyond both being constructed
     together in one function. Fixed by deleting `PrimitiveType` entirely
     and letting `PrimitiveMeasurement`'s own variant serve as the type —
     `PrimitiveFeatures`/`GraphNode` now carry only `measurement`, and
     `GraphMatcher`'s old `primitiveType != primitiveType` gate became a
     `sameKind(measurement, measurement)` check on the sealed variant
     instead. This was a deliberate choice *not* to also move
     `GraphMatcher`'s `sizeScore` logic onto the `Line`/`Arc` classes
     themselves (raised in the same review, as an OOP-dispatch-over-`when`
     argument): `sizeScore`'s normalization constants
     (`MAX_LENGTH_ERROR`, the full-turn scale) are matching-specific tuning
     knobs — Monty's own `EvidenceGraphLM` takes an analogous `tolerances`
     dict as an LM constructor parameter, not something its `SensorModule`
     computes, because a raw sensor reading has nothing to compare itself
     against yet. `PrimitiveMeasurement` lives in the `sensor` package and
     only ever holds one measurement in isolation; the comparison
     (`GraphMatcher`, `CharacterGraphLM`'s matcher) is where two values are
     ever in scope at once, matching where Monty puts this same kind of
     tolerance.
- **A canvas overlay drawing boxes around detected primitives needed their
  on-screen extent, which nothing already stored carried.** `GraphNode`'s
  `location` lives in normalized space (translate-by-centroid, scale-by-
  bounding-radius — see §3.6), not the raw touch-pixel space the canvas
  draws in, and `absoluteAngle`/`measurement` alone don't pin down a
  rectangle's corners anyway (an arc's extent isn't just its center +
  radius). Two options: invert the normalization transform to map
  `location` back to pixels, or track the primitive's actual on-screen
  points directly. Went with the latter — `MontyOrchestrator.replay()`
  already resamples+smooths every stroke into fixed-size point arrays
  *before* calling `StrokePreprocessor.normalize()` on them
  (`buildNormalizedObservations()`); stashing that pre-normalization array
  (`rawResampledPerStroke`) means a primitive's `PrimitiveFeatures.startIndex`/
  `endIndex`/`strokeIndex` (already carried on its `CmpMessage`, previously
  unused past `PrimitiveSensorModule`) slice directly into it, and a min/max
  over that slice is the exact on-screen bounding box — no inverse-transform
  math, no approximation. This became a new `PrimitiveOverlay(measurement,
  topLeft, bottomRight)` type and `MontyOrchestrator.currentPrimitiveOverlays()`
  query, following the same "direct introspection query, not a `CmpMessage`"
  pattern as `evidenceSnapshot()`/`currentNodes()`. `DrawingCanvas` draws one
  rectangle per overlay (padded out to a minimum visible size, since a
  line's own bounding box is legitimately near-zero-width by construction)
  with a text label reusing `LmStateOverlay`'s `describe(measurement)`
  formatting, so the two debug views of the same data read identically.
- **The new overlay rendered blurry.** `DrawingCanvas`'s single `Canvas`
  had `.blur(STROKE_FEATHER_RADIUS)` on its modifier chain to feather the
  stroke's own edges (a soft pencil look) — but `.blur()` is a graphics-
  layer effect that blurs *everything* that `Canvas`'s `DrawScope` draws,
  not just the stroke lines it was meant for, so the overlay rectangles and
  labels drawn in the same `DrawScope` came out blurred too. Fixed by
  splitting into two stacked `Canvas`es in one `Box` — the original
  (blurred) one keeps the strokes and all touch input handling, and a new
  unblurred one on top draws just the primitive overlay. Both `fillMaxSize()`
  the same `Box`, so their coordinate spaces line up exactly with no extra
  mapping needed.
- **Real hand-drawn curves were segmenting into a handful of meaningless
  `LINE` fragments instead of staying as one or two smooth `ARC`s** — the
  bug report that prompted this. Root cause, found by reproducing the
  reported S-curve shape as a unit test and inspecting per-point curvature
  (same empirical methodology as every other calibration pass in this
  section): `StrokePreprocessor.tangentsAndCurvatures`'s `curvature` was
  always a bare per-resampled-step turning angle, not real 1/radius
  curvature — its own doc comment even flagged this as a known
  simplification "sufficient for Phase 2's tangent-stability segmentation"
  at the time. A bare turning angle is *not* invariant to how many points a
  given curve happens to get out of the fixed `DEFAULT_RESAMPLE_COUNT`
  budget a stroke is resampled to: the exact same true semicircle measures
  roughly double the per-step turning angle when it only gets half that
  budget (because it's sharing a stroke with a second lobe of an S, say)
  than when it gets the whole budget to itself. `PrimitiveSensorModule`'s
  sharp-corner veto (`MAX_LOCAL_TURN`) compares this value against a fixed
  threshold meant to catch genuine corners — calibrated only against
  single-primitive synthetic shapes, it had no way to account for this, so
  a perfectly smooth arc sharing a stroke with other primitives could
  spuriously exceed it and get chopped into fragments at every "corner"
  candidate point.
  - **Fix**: `tangentsAndCurvatures` now divides the turning-angle delta by
    the arc length it spans, giving real differential curvature (κ = dθ/ds,
    radians per unit length, ≈ 1/radius for a circle) — a measure that
    genuinely doesn't care how densely a curve got resampled. Verified
    empirically: a tight full loop and a lone semicircle, resampled at very
    different densities, now both measure curvature close to 1.0-1.2
    (matching their shared unit radius), where the old bare-angle measure
    gave wildly different numbers depending on point count.
  - **`MAX_LOCAL_TURN` recalibrated** against the same corner-angle-sweep/
    tight-loop boundary cases as before, now in the new units: legitimate
    arcs (tight loop ~1.0, lone semicircle ~1.2, a 170-degree near-straight
    bend ~1.3) must not trip it; the shallowest corner this app still needs
    to catch (150 degrees interior) measures ~4.0. The gap here (~1.3 to
    ~4.0, roughly 3x) is far more comfortable than the old bare-angle
    version's (~15 to ~20 degrees, roughly 1.3x) specifically *because*
    true curvature actually separates "smooth, however tight" from
    "genuinely sharp" — a resample-density-dependent angle only
    accidentally managed that separation for the single-primitive shapes it
    was tuned against.
  - **A second, orchestrator-specific bug surfaced by this fix**:
    `MontyOrchestrator.buildNormalizedObservations()` computed curvature on
    each stroke's points *before* the shared normalization step, as a
    documented optimization — valid when curvature was a bare turning angle
    (rotation/scale-invariant, so pre- and post-normalization gave the same
    answer), but silently wrong once curvature became length-normalized
    (which very much depends on absolute scale). A small raw-pixel shape
    like a synthetic test "L" measured a corner curvature roughly an order
    of magnitude smaller than it should have in the shared normalized
    space the rest of the pipeline (including `MAX_LOCAL_TURN` itself)
    operates in — so the corner veto silently never tripped, and a taught
    "L" got stored as `[ARC, LINE]` instead of `[LINE, LINE]`, spuriously
    matching unrelated arc-shaped input. This one only reproduced through
    the real orchestrator, not by driving `PrimitiveSensorModule` directly
    (which already computed curvature correctly via `StrokePreprocessor.
    preprocess`'s own normalize-then-curvature ordering) — exactly the kind
    of divergence a "two code paths computing the same thing" duplication
    invites. Fixed by computing curvature on the post-normalization
    `normalizedChunk` instead of the pre-normalization `resampled` points.
- **The curvature fixes above still weren't enough — real hand-drawn curves
  kept fragmenting**, this time on a small, tightly-curved hook (e.g. the
  top of a "2") sharing a stroke with a much longer, straighter tail. This
  wasn't a threshold-calibration problem at all, and no amount of
  recalibrating `MAX_LOCAL_TURN` could have fixed it: the hook's *true*
  curvature, correctly measured, genuinely is large *relative to the
  character's own shared normalized space* — because that space's scale is
  set by the much-bigger tail, the hook's own radius is a small fraction of
  it, and 1/(small fraction) is unavoidably a big number. Confirmed by
  reproducing the exact shape as a unit test: the hook's curvature measured
  a stable, converged ~4.0-4.1 even across a wide range of resample
  densities (48 to 200 points) — ruling out under-resolution as the cause.
  **No single absolute curvature threshold, however it's computed, can
  separate "a small feature next to a much bigger one" from "a genuine
  corner"** when both are judged against the same shared, character-wide
  normalized scale — a structural limitation of that whole approach, not a
  mistuned number.
  - **Fix**: replaced the per-point curvature veto (`MAX_LOCAL_TURN`)
    entirely with a *tighter, arc-specific* residual tolerance
    (`ARC_WIDTH_TOLERANCE`, separate from `LINE_WIDTH_TOLERANCE`, both of
    which used to share one `WIDTH_TOLERANCE`). This targets the veto's
    actual original purpose — stopping a circle's 3 degrees of freedom from
    trivially "absorbing" a moderate corner's two legs — at its real
    source: measured residuals showed corners down to 130 degrees interior
    already fail even the old shared 0.05 tolerance (residual 0.07-0.15);
    the real gap was only 150-170 degrees (residual ~0.040/~0.013) sneaking
    under it. Every legitimate arc this app needs — a tight full loop, a
    bare semicircle, a shallow 30-degree arc, and the small hook above —
    measures residual under 0.0035 *regardless of the character's overall
    size*, because a residual-to-tolerance check only ever compares a run
    against its own best-fit circle, never against anything outside that
    run. `ARC_WIDTH_TOLERANCE = 0.008` sits with a comfortable margin on
    both sides of the 150-170-degree gap. This is the property the old
    curvature veto could never have, no matter how it was calibrated: a
    quantity that's genuinely local to the run being classified, not
    entangled with whatever else happens to share its character.
  - `DecodedPoint.curvature` (and the whole per-point curvature veto branch
    in `step()`) removed from `PrimitiveSensorModule` as dead code — nothing
    in this class reads curvature anymore. `StrokeFeatures.curvature` still
    gets computed and emitted by `TouchSensorModule` (an ordinary SM-level
    feature, matching real Monty's own SMs computing local curvature) —
    it's just not this particular consumer's concern anymore.
  - Regression tests added for both concrete repros: the small-hook-plus-
    long-tail case (must segment as exactly `[ARC, LINE]`), and the gentle
    S-curve case from the earlier curvature fix (relaxed from "at most 2
    primitives" to "at most 3, none degenerate" — a small, non-degenerate
    line/arc boundary call on the S's second lobe is fine; five spurious
    near-zero-length fragments is not).
- **Even the `ARC_WIDTH_TOLERANCE` fix above wasn't the end of it: a real,
  single, gently-curving stroke (from an actual screenshot) still split
  into two straight lines meeting at a fake corner.** At that point — five
  recalibration rounds deep, each fixing the specific shape that broke it
  while leaving the *next* shape broken — the actual conclusion was that
  the *category* of fix was wrong, not the specific numbers: forcing every
  window of a continuously-varying hand-drawn stroke into one of a small,
  fixed, hand-designed shape vocabulary (`LINE`/`ARC`) via *any* geometric
  distance threshold is inherently lossy. A real stroke sits on a continuum
  between straight and curved; some stroke will always land right on
  whatever boundary a threshold draws, no matter how well-calibrated.
  Confirmed by reading real Monty's own source
  (`/Users/gtashkinov/Repositories/tbp.monty/`): it has no analogous
  step anywhere. Monty's SM emits dense per-point local features (computed
  over a small *fixed* neighborhood, e.g. `principal_curvatures` in
  `sensor_modules.py`) and its LM matches them via *evidence accumulation
  against learned templates* — never rounding a continuous shape into a
  hand-designed taxonomy.
  - **The redesign**: `PrimitiveSensorModule` deleted outright, replaced by
    two new pieces, both directly reusing existing infrastructure rather
    than inventing a parallel system:
    1. **`PrimitiveGraphLM`** (new, `lib/.../lm/PrimitiveGraphLM.kt`) — a
       genuine taught `LearningModule` for Tier 1, structurally parallel to
       `CharacterGraphLM`: the user teaches primitive shapes (`"line"`,
       `"arc"`, or whatever labels naturally arise) by drawing an example
       and labeling it, exactly like teaching a character. Given a
       candidate window, it resamples to a small fixed point count (12),
       normalizes it, and matches it against every taught template via the
       *same* order/direction-tolerant alignment search `GraphMatcher`
       already used at the character level — extracted into a new shared
       `AlignmentSearch` (`lib/.../util/AlignmentSearch.kt`) parameterized
       by a per-node scorer, rather than forcing Tier 1's dense point
       windows and Tier 2's sparse primitive sequences to share one node
       schema (a design-review recommendation: share the *algorithm*, not
       the *data shape*). Because a primitive window is short (~12 points,
       not a whole ~48-point stroke), this brute-force search stays cheap —
       no new nearest-neighbor evidence engine was needed.
    2. **`StrokeSegmenter`** (new, `lib/.../orchestrator/StrokeSegmenter.kt`)
       — a classic Viterbi-style dynamic-programming segmentation (the same
       shape as segmenting text into dictionary words, scored by a language
       model), replacing the old per-point local threshold decision with a
       *global* one: find the partition of a whole stroke into windows that
       maximizes total score, where each window's score comes from
       `PrimitiveGraphLM`'s evidence. This directly uses the "maintain
       multiple hypotheses, let evidence decide" principle Monty already
       uses for object recognition, just applied one level below character
       recognition too — a wrong segmentation that matches nothing scores
       no worse than a "correct" one that also matches nothing, so no local
       decision has to be independently perfect. Deliberately generic
       (takes only a point count and a score callback), so it's fully
       unit-testable against synthetic score functions with no real
       geometry involved.
    3. **`PrimitiveMeasurement`** collapsed from a closed `sealed interface`
       (`Line(length)` / `Arc(sweepAngle, radius)`) to one open
       `data class PrimitiveMeasurement(label: String, extent: Float)` —
       primitives are now arbitrary taught labels, not a fixed two-variant
       enum, and `extent` (a chord-length-based size, comparable across any
       label) is a genuine simplification of `GraphMatcher`'s
       `sameKind()`/`sizeScore()`, not just a generalization for its own
       sake.
    4. **`MontyOrchestrator.replay()`** rewritten to run `StrokeSegmenter`
       per stroke (never bridging a primitive across a pen lift, the same
       invariant the old design enforced), then construct each chosen
       window's `CmpMessage` directly and feed it to
       `CharacterGraphLM.matchingStep()` exactly as before — Tier 2 is
       untouched apart from the `PrimitiveMeasurement` type change.
  - **A real consequence found while wiring this in, not planned for
    up front**: this segmentation search needs the *whole* stroke's points
    before it can decide anything, querying many candidate windows that
    never get chosen — it is not a per-observation streaming step the way
    a real Monty `SensorModule.step()` is. `TouchSensorModule` (whose only
    job was wrapping one point into a `CmpMessage` for the old
    `PrimitiveSensorModule` chain) had no remaining consumer once that
    chain was gone, so it — and the now-pointless `StrokeFeatures`
    interface — were deleted alongside it rather than kept as unused
    pass-throughs. `CharacterGraphLM` still receives the same uniform
    `CmpMessage` stream it always did; only *how* those messages get
    produced changed.
  - **Three real bugs found empirically while validating this against the
    exact failure cases that motivated it** (same measure-then-fix
    methodology as every calibration pass in this section) — none of them
    hypothetical, all caught by actually running the new pipeline against
    real repro shapes before declaring it done:
    1. **The DP summed raw evidence, not log-evidence, so it always
       preferred more, shorter primitives.** A clean match against a taught
       line scores close to 1.0 *regardless of window length* (any
       sub-segment of a straight line is itself a straight line), so
       summing raw evidence directly meant more windows almost always
       out-scored fewer — no `segmentPenalty` could fix this by itself,
       since a small positive-per-window gain still always wins by using
       more of them (confirmed: a single straight stroke fragmented into
       13 pieces with `segmentPenalty = 0.3`). Fixed by scoring each window
       with `ln(evidence)` instead of raw evidence: log-evidence caps out
       at 0 for a perfect match, so covering the same span with more
       equally-good windows sums to *the same* total, not more — exactly
       how a real language model's word segmentation naturally prefers
       fewer, better-fitting words without being told to. `segmentPenalty`
       dropped to a small tie-breaking constant (`0.05`) once it no longer
       had to fight a runaway positive sum.
    2. **`MAX_WINDOW_LENGTH` (initially 24, half the 48-point resample
       budget) forced even a perfectly straight, whole-stroke line into two
       artificial pieces purely because it exceeded the cap.** Two
       collinear, same-direction primitive nodes are structurally
       compatible (matching node count) with *any other* two-primitive
       shape, and any bent two-segment shape's node centroids happen to
       land near the same diagonal a straight line's two halves do (once
       each is independently normalized to its own bounding circle) — so
       an untaught "L" spuriously matched an already-taught "line" character
       on position alone. Fixed by setting `MAX_WINDOW_LENGTH` to the whole
       per-stroke resample budget (`StrokePreprocessor.DEFAULT_RESAMPLE_COUNT`)
       instead of an arbitrary smaller number — segmentation is already
       scoped per stroke, so the natural upper bound on one primitive is
       "the whole stroke," not a smaller cap invented without a reason tied
       to anything else in the pipeline.
    3. **`PrimitiveGraphLM` had no rotation invariance — only translation
       and scale (via `StrokePreprocessor.normalize`).** A "line" taught at
       45 degrees barely matched a fresh *vertical* line on evidence
       (~0.75, not ~0.99), because position comparison used each window's
       raw (translated+scaled but unrotated) coordinates — identical shapes
       at different absolute orientations have very different raw
       positions even after centering and scaling. `GraphMatcher`'s
       angle re-baselining trick only fixes *tangent-angle* comparison,
       not position. This was the actual reason corners went undetected
       once the `MAX_WINDOW_LENGTH` fix above was applied: a leg of an "L"
       resampled and normalized on its own scored only ~0.75 against a
       differently-oriented line template, not enough to reliably beat one
       big mediocre match end to end. Fixed by rotating every window
       (position *and* tangent angle, consistently) so its own first
       tangent points along a fixed axis, in `PrimitiveGraphLM.resample()`
       — recognizing *which* shape a window is is now fully orientation-
       independent, while *how it's oriented* stays exactly where it
       belongs, in `GraphNode.absoluteAngle` at the character level. This
       single fix resolved every remaining test failure at once.
  - **Verified against all three concrete real-drawing failures this
    section documents**, end to end through the real orchestrator, each
    now a permanent regression test in `MontyOrchestratorTest`: the small
    hook next to a long tail segments as exactly `[arc, line]`; the single
    gently-curving stroke stays one cohesive primitive; the S-curve
    segments into a small, non-degenerate handful of primitives. All three
    were the exact shapes that broke five consecutive rounds of the old
    hand-coded classifier.
  - **What's deliberately not done yet**: `app`'s UI still only knows how
    to teach characters — there's no "teach primitives" screen, so
    `RecognizerViewModel`'s `PrimitiveGraphLM` starts with (and stays at)
    zero taught primitives, meaning nothing can be recognized end-to-end
    through the real app yet. `MIN_WINDOW_LENGTH`/`SEGMENT_PENALTY` are
    initial values validated only against the specific repro shapes above,
    not a broad sweep of real handwriting — expect further calibration
    once real primitives are taught and real characters are drawn, the
    same iterative process every other constant in this file's history
    went through. (Resolved by Phase 5's teach-primitives UI, below —
    the calibration itself is still open.)
- **Why aren't `PrimitiveGraphLM` and `CharacterGraphLM` one shared
  `EvidenceGraphLM`, the way real Monty reuses one `EvidenceGraphLM` class
  at every hierarchy level?** A fair question once both tiers exist as
  taught, evidence-matched recognizers — reading real Monty's own source
  (`tbp.monty/src/tbp/monty/frameworks/models/evidence_matching/learning_module.py`,
  its 2-level config `conf/monty/learning_module/evidence_2lm.yaml`)
  confirmed it genuinely *is* the same class at every level there, same
  graph-building/evidence-matching code path regardless of whether an
  input channel is a raw SM or a child LM's own output message — only
  per-instance config differs, never a code branch.
  - **A first pass at porting that 1:1** — one generic `EvidenceGraphLM<F>`
    class, `PrimitiveGraphLM` becoming a real step-driven `LearningModule`
    fed one `CmpMessage` per raw point — was stress-tested by a
    design-review pass and rejected, for concrete reasons, not vibes: (1)
    a generic scorer that always folds in a third term (even a neutral
    default) would silently shift `PrimitiveGraphLM`'s score formula from
    today's 2-term `(angle+position)/2` toward a 3-term average, moving
    `MERGE_THRESHOLD`/`xPercentThreshold` decisions at the margins without
    any test catching it; (2) routing Tier 1's dense per-point windows
    through `CharacterGraphLM`'s `CmpMessage`/pose-vector conversion path
    would mean building a 2x2 rotation matrix from a scalar tangent angle
    purely so the shared code could immediately `atan2` it back — invented
    work, roughly 1,176 times per stroke (`StrokeSegmenter`'s DP tries
    that many candidate windows on a 48-point stroke), for nothing; (3)
    Monty's own step contract is one-shot per real timestep — nothing in
    Monty re-runs an LM's full step contract against dozens of
    speculative, ultimately-discarded candidates the way this app's DP
    segmentation search does, so "same class as Monty" doesn't actually
    transfer as an argument once you look at the access pattern; (4) a
    merge threshold (`0.75f`) tuned for character graphs protected by a
    hard structural label-sequence gate has no reason to be right for
    ungated dense primitive point-clouds, where a bad merge would be a
    silent, invisible blur (no UI exposes Tier 1's templates); (5) making
    `GraphNode`/`GraphObjectModel` generic reaches past `lib` into `app`'s
    `RecognizerViewModel`/`LmStateOverlay`, real churn for a benefit
    (merge/spawn + persistence for Tier 1) nothing had asked for.
  - **What actually shipped instead**: the one piece of logic that
    genuinely was duplicated — the angle-rebaseline + position-error
    scoring loop inside `GraphMatcher.alignmentScore` and
    `PrimitiveGraphLM.alignmentScore` — extracted into one shared generic
    function, `alignmentScore<T>` in the new `lib/.../util/AlignmentScore.kt`
    (`angleOf`/`positionOf` extractors, an optional hard `gate` for Tier
    2's label-sequence check, an optional `extraTerm` for Tier 2's size
    score — absent, not defaulted-neutral, when Tier 1 calls it, so the
    2-term-vs-3-term formula difference can't silently blur). Both call
    sites shrink to a few lines of wiring; every other difference between
    the tiers — episode lifecycle, orientation invariance, the structural
    gate, merge/spawn — stays exactly as different as it deliberately
    already was. Same "share the algorithm, not the schema" principle
    `AlignmentSearch`'s own extraction (above) already established. Pure
    internal refactor: all 76 `lib` tests passed unchanged, no test file
    needed a single edit.
- **Phase 5: the teach-primitives UI, closing the gap flagged above.**
  `RecognizerViewModel` gains a `TeachMode` (`PRIMITIVES`/`CHARACTERS`,
  defaulting to `PRIMITIVES`) and reads/writes `PrimitiveGraphLM` directly
  — deliberately *not* through `MontyOrchestrator`, whose whole reason to
  exist (coordinating segmentation search during a character episode) has
  nothing to do with teaching one primitive in isolation, and whose own
  `teach(label)` is hard-wired to the character tier only.
  - **A primitive is one completed stroke, not a UI convenience but a
    `lib` constraint**: `PrimitiveGraphLM.resample()` derives a single
    orientation reference from the very first tangent over a flat
    `List<RawPoint>` with no stroke-boundary concept at all — a pen-lift
    discontinuity mid-window would fabricate a bogus segment spanning the
    gap and corrupt the whole canonicalization, not just the seam.
    Documented directly on `PrimitiveGraphLM.evaluate`/`teach`'s KDoc so
    this is a stated non-goal, not a silently-discovered one; multi-stroke
    primitives would need their own per-stroke normalization, mirroring
    `MontyOrchestrator.buildNormalizedObservations`'s pattern.
  - **Flow mirrors the character mode's existing lock/review split** at
    stroke granularity instead of character granularity: draw one stroke
    → canvas locks → `primitiveGraphLM.evaluate(...)` runs once and its
    evidence is shown via the *same* `EvidenceBars` composable characters
    already used (fully generic despite its doc comment, needed zero
    changes) → a label field + "Teach"/"Redo" appear
    (`PrimitiveTeachingPanel`, a new file, deliberately not sharing a
    sub-composable with `RecognitionResultPanel` — the two diverge
    immediately once "Redo" and the lack of any recognize/confirm/correct
    branching are accounted for) → teaching immediately unlocks the canvas
    for the next example, no separate "Next" click needed, since there's
    no result to review at this tier, only evidence-as-hint and teach.
  - **"Live evidence" means what it already means elsewhere in this app**:
    recomputed once per completed stroke (matching `EvidenceBars`'/
    `refreshLmState()`'s existing granularity for characters), not
    continuously per motion event — nothing else in the app updates live
    at that finer grain, and `DrawingCanvas`'s in-progress stroke state is
    private to that composable, not hoisted anywhere a live-evidence
    computation could reach it mid-stroke.
  - **A defense-in-depth guard, not just a disabled button**:
    `onStrokeCompleted` now also refuses to start a character episode
    while `taughtPrimitiveLabels` is empty. Without it, nothing stops
    `MontyOrchestrator` from happily running anyway — every primitive
    would just degrade to its already-existing `UNTAUGHT_LABEL =
    "unknown"` — but a character *taught* on top of that would
    permanently poison `GraphMemory` with a model built entirely from
    `"unknown"` nodes, not a crash but a silent, hard-to-diagnose dead end.
    The guard lives in `RecognizerViewModel`, not `lib`:
    `CharacterGraphLM`/`MontyOrchestrator` are correctly ignorant of
    "primitive curriculum state" as a concept.
  - **Mode switch uses two plain `Button`/`OutlinedButton`s**, not
    Material3's segmented-button component — every other control in this
    app already uses plain buttons, and introducing a new visual idiom for
    what's functionally one binary toggle wasn't worth it.
  - **Deliberately deferred, not forgotten**: no delete/review UI for
    individual taught primitive examples — `PrimitiveGraphLM.teach()`
    never merges (every example kept forever, by design) and `evaluate()`
    takes a `max` over each label's examples, so one bad example can only
    help-or-be-neutral for its own label but can hurt discrimination
    against others, with no way today to see or undo it. `LmStateOverlay`
    gained a "Taught primitives (Tier 1)" line (label/count only) for
    partial visibility; a real per-example inspector is Phase 7's
    "debug/inspector screen listing learned labels, viewable/deletable
    variants," not new scope invented here.
  - Design was stress-tested by a review pass before implementation (same
    practice as the two entries above) — it confirmed the single-stroke
    constraint is real (not a scoping choice), confirmed direct
    ViewModel→`PrimitiveGraphLM` access is the right layering, and pushed
    back specifically on introducing `SegmentedButtonRow` as an
    unnecessary new visual idiom.
  - Verified via `./gradlew :lib:test :lib:ktlintCheck :app:ktlintCheck
    :app:assembleDebug` — all 76 `lib` tests still pass (only a KDoc line
    touched `lib`), both modules' ktlint clean, `app` compiles and
    assembles. No adb/device testing performed (standing project rule);
    on-device verification of the new flow is the user's to do.
  - **Follow-up**: primitive teaching gained the same recognize/confirm/
    correct/disambiguate flow characters already had, instead of always
    prompting for a blank label. `RecognitionResult` (and the
    percent-of-max thresholding that builds one from an evidence map) was
    pulled out of `CharacterGraphLM.kt` into its own file,
    `lib/.../lm/RecognitionResult.kt`, as two pure top-level functions
    (`possibleMatches(evidence, threshold)`, `recognitionResult(evidence,
    threshold)`) operating on any `Map<String, Float>` — there was nothing
    character-specific in that type or logic to begin with, just historical
    proximity to where it was first needed.
    `RecognizerViewModel.onPrimitiveStrokeCompleted` now calls
    `recognitionResult(primitiveEvidence)` the same way `CharacterGraphLM`
    calls it on its own `evidenceSnapshot()`, and `PrimitiveTeachingPanel`
    grew the same three-way branching `RecognitionResultPanel` already had
    — plus a "Redo" button on every branch, since a primitive is one cheap
    stroke to redraw where a completed character (once "Done" is pressed)
    has no equivalent discard-and-retry path. Also: the on-canvas primitive
    overlay in character mode no longer requires the LM-state debug panel
    to be expanded — it was gated behind `showLmState` for no real reason
    (the overlay is meant for the user teaching characters, not just for
    debugging) and now always renders.
- **Matching moved off the main thread — the app was freezing.** Every
  `RecognizerViewModel` method called directly and synchronously into
  `MontyOrchestrator`/`CharacterGraphLM`/`PrimitiveGraphLM`/`GraphMemory`
  from Compose event handlers, i.e. on the main thread; as taught
  templates/characters accumulated, this visibly blocked rendering and
  input. Coroutines weren't used anywhere in this codebase before this —
  confined entirely to `app`; `lib` gets no coroutine dependency and stays
  the plain, synchronous, deterministic module it's designed to be.
  - **Design**: all access to `orchestrator`/`tier2`/`primitiveGraphLM`/
    `memory` (plain, non-thread-safe mutable Kotlin objects) is confined to
    one serial dispatcher, `Dispatchers.Default.limitedParallelism(1)` —
    chosen over a dedicated single-thread executor since it's a lightweight
    view with no OS thread to leak, and (verified against
    `kotlinx.coroutines.internal.LimitedDispatcher` source, not just
    documentation) its worker count is CAS-bounded to `parallelism`, so at
    `parallelism = 1` there's always exactly one active worker draining a
    strict FIFO queue. That FIFO guarantee holds relative to real user-
    action order specifically because every dispatch originates from a
    `viewModelScope` coroutine started on `Dispatchers.Main.immediate` from
    an already-Main-thread Compose callback, which runs synchronously (no
    re-dispatch) in that case.
  - **The subtle part**: each method bundles its mutation *and* its
    subsequent state read into one `withContext(matchingDispatcher) { }`
    block, never a mutation in one dispatch and a read in a separate,
    later one. Tracing a concrete interleaving (a stroke completes, then
    "Undo" is tapped before the first stroke's matching finishes) showed
    why: both dispatches correctly FIFO-serialize relative to each other,
    but a *separately*-dispatched read for the first action could still
    run on Main concurrently with the second action's already-started
    background mutation — a genuine data race on `lib`'s mutable state
    (e.g. `CharacterGraphLM.evidenceSnapshot()`'s iteration racing
    `preEpisode()`'s `nodeBuffer.clear()` from the next queued operation).
    Bundling read with write makes each user action one atomic hand-off to
    the serial dispatcher — a shared `LmSnapshot` data class + `captureLmState()`/
    `applyLmState()` pair in `RecognizerViewModel` formalizes this pattern
    once for every method that needs it.
  - **The one real gap a design-review pass found**: this "serial log of
    additive operations" model breaks down for `MontyOrchestrator.endCharacter()`
    specifically, since it's a phase transition, not another log entry
    (its own doc already says so — `postEpisode()`'s real effect is
    deliberately reserved for it, never fired on a plain `replay`). Left
    unguarded, a "Done" tap followed by a queued Undo/Clear/stroke would
    run correctly *ordered* but semantically wrong — retroactively
    mutating an episode already being finalized, leaving `result` showing
    a decision for strokes that no longer match what's on screen. Fixed
    with a synchronous `isEndingCharacter` guard flipped the instant
    "Done" is pressed, before dispatch — mirroring how `primitiveStroke =
    points` already instantly locks primitive mode's canvas — and threaded
    into `DrawingScreen.kt`'s canvas/toolbar gating so the UI locks
    immediately rather than only after the round trip completes. A
    correctness fix, not new progress-UI chrome (no spinner was added).
  - **A second, smaller gap with a free fix**: `RecognizedPanel`'s
    "Confirm" button had no `enabled` gate, so a fast double-tap during the
    async round trip could call `orchestrator.teach(...)` twice — the
    second call, matching against the model it just merged, merges *again*
    rather than spawning a new variant, silently inflating `exemplarCount`
    and skewing future merge weighting. Fixed by setting `taughtLabel`
    synchronously before dispatch in `onTeach`/`onConfirm` (its value is
    always already known at that point) — closes the double-teach hole and
    makes the "Taught 'x'" confirmation appear instantly instead of
    waiting on the round trip, strictly better on both axes. `onConfirm`
    now simply delegates to `onTeach`, removing what had been duplicated
    logic.
  - Design was stress-tested by a review pass before implementation (same
    practice as every non-trivial change this session) — it verified the
    `limitedParallelism(1)` FIFO claim against actual library source rather
    than accepting it on faith, confirmed cancellation/consistency weren't
    issues, and found both gaps above by tracing the real UI gating code
    (`DrawingScreen.kt`/`RecognitionResultPanel.kt`), not just the
    ViewModel in isolation.
  - `kotlinx-coroutines-android` added as an explicit `app`-only dependency
    (via `gradle/libs.versions.toml`, matching this catalog's existing
    explicit-declaration style) — it was already being pulled in
    transitively through `androidx.lifecycle:lifecycle-viewmodel-ktx`, but
    this is the first place the app calls coroutine APIs directly rather
    than only using the `viewModelScope` property, so declaring it
    explicitly rather than continuing to rely on an undeclared transitive
    was the right call.
  - Verified via `./gradlew :lib:test :lib:ktlintCheck :app:ktlintCheck
    :app:assembleDebug` — `lib` is untouched (all 76 tests still pass),
    both modules' ktlint clean, `app` compiles and assembles. `app` still
    has no ViewModel unit-test coverage (established convention) — a real
    gap for a concurrency-sensitive change specifically, since this is
    exactly the class of bug automated tests catch and manual testing
    doesn't, but `kotlinx-coroutines-test` infrastructure wasn't added as
    part of this fix. No adb/device testing performed (standing project
    rule); confirming the freeze is actually gone on a real device is the
    user's to do.
- **`SensorModule<T>` had drifted to zero implementations.** The Phase-4
  redesign (this section's entry above, "The redesign") deleted the
  original `PrimitiveSensorModule` outright and never reinstated anything
  behind the interface: `MontyOrchestrator` itself grew the resample/
  normalize/segment/emit-`CmpMessage` logic inline (`windowScore()`,
  `emitPrimitive()`), and `PrimitiveGraphLM` was queried directly by both
  `MontyOrchestrator` and `RecognizerViewModel`. Real Monty never does this
  — confirmed by reading `frameworks/models/two_d_sensor_module.py`:
  `TwoDSensorModule` stays a concrete `SensorModule` subclass even though
  its own feature extraction (edge detection, surface-normal fitting) also
  needs more than one raw sample, the same situation this app's primitive
  segmentation is in. Needing whole-stroke context turned out not to be a
  real reason to abandon the interface.
  - **Fix**: reinstated a concrete `PrimitiveSensorModule : SensorModule
    <RawTouchObservation>` (`lib/.../sensor/PrimitiveSensorModule.kt`) —
    a different class from the original, since-deleted one of the same
    name, wrapping `PrimitiveGraphLM` rather than a hand-coded classifier.
    `step()` buffers each observation and returns a `passMessage = false`
    placeholder (a segmentation decision genuinely can't be made
    per-point); `flushStroke()` — this class's one deliberate addition
    beyond the plain interface, same spirit as the original class's
    `drainTrailingPrimitive()` — runs `StrokeSegmenter`'s search once a
    whole stroke's observations have all been stepped, and drains the
    resulting primitives as a batch of `CmpMessage`s.
    `MontyOrchestrator.replay()` now steps each stroke's observations
    through it and calls `flushStroke()` at each stroke boundary, instead
    of doing the segmentation/matching/message-construction work itself;
    `windowScore()`/`emitPrimitive()`/their helpers moved to the new class
    verbatim. `buildNormalizedObservations()` (the shared, whole-character
    normalization) stayed in `MontyOrchestrator` — it's cross-stroke,
    episode-scoped state, not this sensor's concern, and `PrimitiveGraphLM`
    itself is unchanged (still deliberately not a `SensorModule` or
    `LearningModule` — see its own class doc, updated to point at the new
    `PrimitiveSensorModule` as its actual caller instead of
    `MontyOrchestrator`).
  - Doc comments elsewhere that referenced the *original* `PrimitiveSensorModule`
    by its old behavior (`StrokePreprocessor`'s curvature note, `StrokeSegmenter`'s
    class doc) were reworded to distinguish it from the current class of the
    same name, since a bare `[PrimitiveSensorModule]` KDoc link now resolves
    to the new one.
  - New `PrimitiveSensorModuleTest.kt` covers the class's own `SensorModule`
    contract directly (buffering in `step()`, batch draining in
    `flushStroke()`, reset in `preEpisode()`); the segmentation/matching
    *behavior* against real drawn shapes stays covered by the existing
    `MontyOrchestratorTest`, unchanged and still passing (exercises this
    class through the real orchestrator, no internal mocking needed).
  - Verified via `./gradlew :lib:test :lib:ktlintCheck :app:ktlintCheck
    :app:compileDebugKotlin` — all 12 `MontyOrchestratorTest` cases pass
    unchanged (behavior parity), the new test class's 5 cases pass, both
    modules' ktlint clean, `app` compiles. No adb/device testing performed
    (standing project rule).
