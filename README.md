# thousand-brains-app
Android app for the Thousand Brains Project

The app learns to recognize handwritten characters **live, from
your own handwriting, with no pretraining** — inspired by (and architecturally
ported from) Numenta's [Thousand Brains Project](https://thousandbrains.org/)
and its reference implementation, [Monty](https://github.com/thousandbrainsproject/tbp.monty).

Draw a character on screen, tell the app what it is, and it starts recognizing
it — including future variations in size, slant, and stroke style. No dataset,
no training phase, no cloud model. It's built to work the way Monty's
sensorimotor learning modules work: bind a moving sensor's observations into a
structured, pose-relative graph, and recognize new instances by accumulating
evidence against that graph as they're traced.

---

## Why this exists

The Thousand Brains Theory claims that the neocortex represents everything it
learns — objects, and in principle abstract concepts — as structured models
built through **movement**: a sensor moves over something, and features get
bound to their *location relative to the thing itself* (not to the sensor, not
to the world), inside thousands of nearly-identical cortical columns that then
vote to reach consensus. Monty is Numenta's working implementation of this,
validated so far mainly on 3D object recognition from simulated touch/vision.

This project asks a narrower, concrete question: **does the same architecture
work for something you can build and use today on a phone?** Touchscreen
handwriting turns out to be an unusually good fit — a finger stroke *is*
already a real sensorimotor trace (unlike a static photo, which requires
reconstructing one), and character recognition is a small enough problem
(dozens of classes, not thousands) that you can implement the real Monty
interfaces rather than a toy approximation of them.

Full background and the reasoning trail that led to this design (including
detours we deliberately didn't take) is worth reading if you're picking this
project up — see `docs/DESIGN_LOG.md` if you keep one, or the conversation
this was designed in.

---

## How it works, in one paragraph

Touch input is captured as raw (x, y, t) points per stroke — a literal
sensorimotor trace, not a reconstruction. There are now **two taught
Learning Modules**, not one: a whole stroke's points are searched by a
global dynamic-programming pass (`StrokeSegmenter`) for the partition into
windows that best matches whatever primitive shapes the user has *taught*
(`PrimitiveGraphLM`) — there's no fixed line/arc vocabulary baked into the
code; a primitive is whatever label the user teaches it, matched purely by
evidence, the same way the character tier already worked. Each chosen
window becomes one message tagged with its pose *relative to the stroke*,
not the screen. The character-level **Learning Module**
(`CharacterGraphLM`) then composes those primitives into a character-level
graph — nodes and edges with relative positions and tolerance-banded
angles — and matches new input against every previously-taught character by
accumulating evidence as the stroke is drawn. If two characters are equally
well supported (the honest "is this a 6 or a 9?" case), the app says so
instead of guessing. Teaching is just: draw, then tell it the label — which
either merges into an existing model (if it's a natural variation) or
creates a new variant under the same label (if it's structurally different,
e.g. a different stroke order). See `IMPLEMENTATION_PLAN.md` §7 for why the
primitive tier moved from a hand-coded geometric fit to this taught,
evidence-matched design.

---

## Architecture

```
Touch (MotionEvent) → SensorModule → MontyOrchestrator                      → CharacterGraphLM → GraphMemory<characters>
                            ↑         (StrokeSegmenter: global DP search       ↑
                   (same CmpMessage    over each stroke, scored by          (evidence accumulation,
                    schema throughout) PrimitiveGraphLM.evaluate)            merge/spawn decisions)
                                              ↑
                                       PrimitiveGraphLM ── GraphMemory-style taught templates
                                       (taught by drawing + labeling, evidence-matched,
                                        no fixed shape vocabulary)
```

This is deliberately **not** a simplified, inspired-by version of Monty's
design — it ports Monty's actual published interfaces:

- **`CmpMessage`** mirrors Monty's CMP `Message` class: `location`,
  `morphologicalFeatures` (pose vectors + `poseFullyDefined`),
  `nonMorphologicalFeatures`, `confidence`, sender identity — the single
  message format used for *every* inter-component exchange, feed-forward or
  lateral (voting). One Kotlin-specific improvement on Monty's own Python:
  `nonMorphologicalFeatures` is typed `Any` rather than a stringly-keyed
  dict, and each producer (a sensor, a tier) declares its own small marker
  interface for its payload (`StrokeFeatures`, `PrimitiveFeatures`) instead
  of `get_feature_by_name("...")` lookups — same message uniformity, no
  unsafe casts.
- **`SensorModule`** / **`LearningModule`** are ports of Monty's abstract base
  classes. `LearningModule`'s real per-step contract: a **modeling step**
  (`matchingStep`, ~ Monty's `matching_step`) followed by a **voting step**
  (`receiveVotes`, ~ Monty's `receive_votes`), a **feed-forward output**
  (`getOutput`, ~ Monty's `get_output` — always a single-hypothesis point
  estimate, never a distribution, matching what Monty itself does), plus
  `preEpisode`/`postEpisode` lifecycle hooks and a `state`/`loadState`
  save-load contract. `SensorModule` is deliberately thinner — `step`,
  `preEpisode`, `postEpisode`, nothing else — because Monty's own sensor
  modules don't vote, don't persist learned state, and don't have
  training-vs-eval behavior; only `LearningModule` does. Every lifecycle
  hook bounds the **same episode for the whole pipeline at once** — one
  full character, however many strokes it takes — mirroring Monty's
  `MontyBase.reset()`, which resets every sensor and learning module
  identically regardless of hierarchy position; there's no per-tier
  episode scoping in Monty, so there isn't one here either.
- **`GraphObjectModel`** / **`GraphMemory`** mirror Monty's own object-model
  storage, down to reusing the concept (and naming intent) of Monty's
  `detect_new_object_k_steps` — the actual mechanism Monty uses to decide
  whether new evidence should merge into an existing learned model or spawn a
  new one.
- **Two taught tiers now, both evidence-matched — no fixed shape
  vocabulary anywhere.** `PrimitiveGraphLM` (Tier 1) and `CharacterGraphLM`
  (Tier 2) are both genuine, taught `LearningModule`-style components: a
  primitive ("line", "arc", or whatever label the user teaches) is
  recognized by matching against taught examples, exactly the way a
  character is. This replaced an earlier `PrimitiveSensorModule` that
  classified strokes into a fixed `LINE`/`ARC` vocabulary via hand-coded
  geometric distance-fit thresholds — recalibrated five times chasing real
  handwriting failures and never converging, because forcing a
  continuously-varying stroke into a small, fixed taxonomy via any hard
  threshold is inherently lossy. Real Monty has no analogous step: it
  emits dense per-point features and matches everything via evidence
  accumulation against *learned* templates, never a hand-designed
  taxonomy. See `IMPLEMENTATION_PLAN.md` §7 for the full redesign story,
  including the real-Monty source citation and Known limitations below.

Full interface listings, code, and an explicit table of what's a faithful
port vs. a deliberate simplification (mainly: 2D instead of 3D, and no
simulated motor/exploration policy, since the user's finger *is* the motor
system here) are in **[`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md)**.

---

## Two layers: `lib` (brain) and `app` (Android)

The codebase is split into two Gradle modules, and the split is enforced at
the build level, not just by convention:

- **`lib`** — a plain **Kotlin/JVM module** (`kotlin("jvm")`, not
  `com.android.library`). It contains *all* of the brain: `CmpMessage`, the
  `SensorModule`/`LearningModule` interfaces, `PrimitiveSensorModule`,
  `CharacterGraphLM`, `GraphObjectModel`/`GraphMemory`/`GraphMatcher`, and
  `MontyOrchestrator` wiring them into a real step loop.
  The Android SDK is not on its classpath, so nothing in it can import
  `android.*` even by accident. Its public API only ever exchanges plain
  Kotlin data (data classes, `Map`/`List`, primitives) — never a
  `MotionEvent`, `Context`, or Compose type.
- **`app`** — the Android application module. It owns everything the brain
  doesn't: capturing `MotionEvent`s and converting them into `lib`'s
  `RawTouchObservation`s, the Compose UI, and on-device persistence
  (writing/reading the data `lib`'s `state()` produces, e.g.
  `CharacterGraphLM`'s learned `GraphObjectModel`s). In the organism
  analogy, `app` is the sensors and motor output — the touchscreen and the
  screen/storage — everything outside the brain.

The payoff: every recognition algorithm (resampling, segmentation, graph
matching, merge/spawn decisions) is testable with plain JUnit on the JVM —
`./gradlew :lib:test` — with no emulator and no Robolectric, in seconds.
`app` stays thin: a couple of adapters and some UI, tested separately and
minimally. See **[`IMPLEMENTATION_PLAN.md`, §2a](./IMPLEMENTATION_PLAN.md#2a-module-boundary-lib-brain-vs-app-android)**
for the full boundary rules.

---

## Key design decisions (and why)

| Decision | Rationale |
|---|---|
| Touchscreen input, not camera/photo | Gives a genuine stroke trajectory (order, direction, boundaries) for free — a photo requires reconstructing one, which is a much harder, separate problem (see "Static input" below) |
| No pretraining | The theory's central claim is fast, few-shot, continual learning — this app is built to actually test that, not sidestep it with a bundled dataset |
| Small rotation tolerance, not full rotation search | Unlike 3D objects, a character's orientation is part of its identity (b/d/p/q, 6/9) — searching full rotation would actively cause misrecognition |
| Multiple graph variants per label, not one generalized template | Real handwriting varies in stroke count/order, not just size/slant; forcing one graph to cover all of it either overfits or corrupts the model |
| Ties are reported, not resolved by a forced guess | A tie is a correct output of evidence accumulation — some character pairs (6/9, O/0) are genuinely, structurally ambiguous from shape alone, and the app should say so |
| Episode boundary is per-character, uniform across every tier | Mirrors Monty's `MontyBase.reset()` applying identically to every SM/LM; a pen lift between strokes is a `strokeIndex` discontinuity handled as message data (mirroring Monty's own on/off-object `use_state` flag), not a separate per-tier lifecycle scope |
| Static image input is a separate, later milestone | Requires trajectory reconstruction (skeleton-glide + multi-LM voting to resolve stroke-order/crossing ambiguity) — a materially harder, well-studied problem in its own right ("handwriting trajectory recovery" in the literature), not a minor variant of the core app |
| `lib`/`app` split, `lib` has zero Android SDK dependencies | Keeps the brain logic unit-testable on the JVM in milliseconds (no emulator, no Robolectric), and makes it obvious at build time — not just by convention — when device-specific code has crept into the wrong place |

---

## Project structure

Two Gradle modules, matching the [`lib`/`app` split](#two-layers-lib-brain-and-app-android) above:

```
lib/                        # Kotlin/JVM module — NO Android SDK dependency
  src/main/kotlin/.../
    cmp/
      CmpMessage.kt           # CmpMessage, CmpGoal, MorphologicalFeatures, SenderType
    sensor/
      RawPoint.kt             # plain 2D point crossing the lib/app boundary
      RawTouchObservation.kt
      SensorModule.kt         # generic interface: SensorModule<T>
      StrokePreprocessor.kt   # resample, smooth, normalize, tangent/curvature
      PrimitiveMeasurement.kt # open label+extent measurement, PrimitiveFeatures payload
    lm/
      LearningModule.kt       # shared interface: matchingStep/receiveVotes/sendOutVote/getOutput
      PrimitiveGraphLM.kt     # Tier 1: taught, evidence-matched primitive recognizer (no fixed shape vocabulary)
      CharacterGraphLM.kt     # Tier 2: evidenceSnapshot(), possibleMatches(), teach()
    orchestrator/
      StrokeSegmenter.kt      # generic global DP segmentation search (Viterbi-style)
      MontyOrchestrator.kt    # runs StrokeSegmenter per stroke, scored by PrimitiveGraphLM, feeds CharacterGraphLM
    memory/
      GraphObjectModel.kt     # GraphNode, GraphEdge, GraphObjectModel, edgeChainOf
      GraphMatcher.kt         # order/direction-tolerant matching (character tier)
      GraphMemory.kt          # merge/spawn logic, snapshot()/restore()
    util/
      Angles.kt               # shared angle-wrapping helpers
      AlignmentSearch.kt      # generic order/direction-tolerant alignment search, shared by both tiers
  src/test/kotlin/.../        # plain JUnit unit tests: resampling, segmentation,
                              # matching, merge/spawn, end-to-end recognition —
                              # runs on the JVM, no emulator, no Robolectric

app/                         # Android application module, depends on :lib
  src/main/kotlin/.../
    sensor/
      TouchObservationAdapter.kt  # Offset -> RawPoint mapping (thin, no recognition logic)
    viewmodel/
      RecognizerViewModel.kt  # owns the MontyOrchestrator, exposes Compose state
    ui/
      DrawingCanvas.kt        # MotionEvent capture + live stroke rendering; debug boxes around detected primitives when LM state is shown
      DrawingScreen.kt        # composes canvas, evidence bars, toolbar/result panel
      DrawingToolbar.kt       # undo/clear/done
      EvidenceBars.kt         # live per-label match evidence
      RecognitionResultPanel.kt # teach/confirm-correct/disambiguate, driven by RecognitionResult
      LmStateOverlay.kt       # debug overlay: primitives this episode + graphs learned
    MainActivity.kt
  src/androidTest/.../        # adapter + Compose UI smoke tests only

IMPLEMENTATION_PLAN.md        # phased build plan, full interface code, mapping table
README.md                     # this file
CLAUDE.md                     # notes for AI assistants working on this repo
```

---

## Status / Roadmap

See **[`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md)** for the full
phase-by-phase plan (Phases 0–7 for the touchscreen app, Phase 8 for the
static-image/multi-LM-voting stretch goal) and testing strategy.

**Phases 0–4 are done:** `lib`'s full brain pipeline — touch resampling,
primitive segmentation, character-graph learning and recognition, and the
`MontyOrchestrator` step loop — plus `app`'s teach/recognize UI are built
and tested end to end. There's no persisted state between runs yet
(Phase 7's job).

**Tier 1 has since been redesigned** (see `IMPLEMENTATION_PLAN.md` §7): the
original hand-coded `PrimitiveSensorModule` (fixed `LINE`/`ARC` geometric
fit tests) is gone, replaced by `PrimitiveGraphLM` (a taught,
evidence-matched primitive recognizer, structurally parallel to
`CharacterGraphLM`) plus `StrokeSegmenter` (a global dynamic-programming
search over each whole stroke). `lib`'s test suite (76 unit tests) passes
against this new design, including regression tests for the specific real
hand-drawn shapes that motivated it. **`app`'s teach/recognize UI has not
been rewired for this yet** — there's no "teach primitives" screen, so a
fresh `PrimitiveGraphLM` starts (and stays) empty and the app isn't
end-to-end functional for a real user until that UI exists. **Phase 5**
(building that UI, plus primitive/segmentation constant tuning against real
handwriting) is next.

---

## Known limitations (by design, not oversight)

- **The primitive tier requires teaching before anything can be
  recognized, by design.** `PrimitiveGraphLM` starts with zero taught
  labels and has no geometric fallback for an untaught shape — a window
  that matches nothing scores no better than any other bad segmentation,
  which correctly surfaces as `RecognitionResult.Unknown` rather than a
  guess. This was a deliberate call during the Tier 1 redesign (see
  `IMPLEMENTATION_PLAN.md` §7): an earlier draft considered a geometric-fit
  fallback for unmatched windows, dropped because it would quietly become
  the dominant path whenever evidence is close, reintroducing exactly the
  brittle hand-coded classification this redesign replaced. The practical
  consequence today: `app` has no "teach primitives" UI yet, so nothing can
  be recognized end-to-end through the app until that's built (Phase 5).
- **No general compositional part-swapping.** Real hierarchical composition
  (recognizing a novel combination of familiar parts) is flagged as immature
  even in Monty's own published work; this app's multi-variant-per-label
  fallback is a pragmatic substitute, not a solved version of it.
- **No solution for genuine shape symmetry.** If two characters are truly
  identical up to a transform the app doesn't search (rotation, in our case),
  no amount of evidence accumulation will separate them — that's expected,
  and the UI is designed to surface it as a tie rather than hide it behind a
  guess.
- **Static/photographed input is unsupported in v1** and, even in the Phase 8
  extension, inherits known-hard cases from the handwriting-trajectory-
  recovery literature (stroke order ambiguity, crossing-point resolution) —
  these are treated as acceptable tie/question outcomes, not bugs to eliminate.

---

## References

- Thousand Brains Project — https://thousandbrains.org/
- Monty (reference implementation) — https://github.com/thousandbrainsproject/tbp.monty
- Monty API reference (CMP, abstract LM/SM classes) — https://api-monty.thousandbrains.org/
- "Thousand-Brains Systems: Sensorimotor Intelligence for Rapid, Robust
  Learning and Inference" — https://arxiv.org/html/2507.04494
- Wobbrock, Wilson & Li, "$1 Unistroke Recognizer" (UIST 2007) — order/
  direction-invariant point-cloud matching, relevant prior art for
  `GraphMatcher.matchScore` — https://depts.washington.edu/acelab/proj/dollar/index.html
- Handwriting trajectory recovery / offline-to-online conversion literature —
  relevant background for the Phase 8 stretch goal.
