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

Touch input is captured as raw (x, y, t) points per stroke — this is the
**Sensor Module**'s input, and it's a literal sensorimotor trace, not a
reconstruction. Points are resampled and normalized, then a **Tier-1 Learning
Module** segments them into primitive shapes (lines, arcs, corners), each
tagged with its pose *relative to the stroke*, not the screen. A **Tier-2
Learning Module** composes those primitives into a character-level graph —
nodes and edges with relative positions and tolerance-banded angles — and
matches new input against every previously-taught character by accumulating
evidence as the stroke is drawn. If two characters are equally well supported
(the honest "is this a 6 or a 9?" case), the app says so instead of guessing.
Teaching is just: draw, then tell it the label — which either merges into an
existing model (if it's a natural variation) or creates a new variant under
the same label (if it's structurally different, e.g. a different stroke
order).

---

## Architecture

```
Touch (MotionEvent) → SensorModule → Tier-1 PrimitiveLM → Tier-2 CharacterGraphLM → GraphMemory
                                              ↑                      ↑
                                     (same CmpMessage         (evidence accumulation,
                                      schema at every tier)    merge/spawn decisions)
```

This is deliberately **not** a simplified, inspired-by version of Monty's
design — it ports Monty's actual published interfaces:

- **`CmpMessage`** mirrors Monty's CMP `Message` class: `location`,
  `morphological_features` (pose vectors + `pose_fully_defined`),
  `non_morphological_features`, `confidence`, sender identity — the single
  message format used for *every* inter-component exchange, feed-forward or
  lateral (voting).
- **`SensorModule`** / **`LearningModule`** are ports of Monty's abstract base
  classes, including the real per-step contract: a **modeling step**
  (`matchingStep`, ~ Monty's `matching_step`) followed by a **voting step**
  (`receiveVotes`, ~ Monty's `receive_votes`), plus `preEpisode`/`postEpisode`
  lifecycle hooks and a `stateDict`/`loadStateDict` save-load contract.
- **`GraphObjectModel`** / **`GraphMemory`** mirror Monty's own object-model
  storage, down to reusing the concept (and naming intent) of Monty's
  `detect_new_object_k_steps` — the actual mechanism Monty uses to decide
  whether new evidence should merge into an existing learned model or spawn a
  new one.
- Both tiers implement the **same `LearningModule` interface** — Monty's
  "repeating computational unit" principle: a higher tier's input looks
  exactly like a lower tier's output, so the hierarchy can in principle be
  extended without new plumbing.

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
  `SensorModule`/`LearningModule` interfaces, `PrimitiveLM`,
  `CharacterGraphLM`, `GraphObjectModel`/`GraphMemory`, `MontyOrchestrator`.
  The Android SDK is not on its classpath, so nothing in it can import
  `android.*` even by accident. Its public API only ever exchanges plain
  Kotlin data (data classes, `Map`/`List`, primitives) — never a
  `MotionEvent`, `Context`, or Compose type.
- **`app`** — the Android application module. It owns everything the brain
  doesn't: capturing `MotionEvent`s and converting them into `lib`'s
  `RawTouchObservation`s, the Compose UI, and on-device persistence
  (writing/reading the bytes `lib`'s `stateDict()` produces). In the
  organism analogy, `app` is the sensors and motor output — the touchscreen
  and the screen/storage — everything outside the brain.

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
| Static image input is a separate, later milestone | Requires trajectory reconstruction (skeleton-glide + multi-LM voting to resolve stroke-order/crossing ambiguity) — a materially harder, well-studied problem in its own right ("handwriting trajectory recovery" in the literature), not a minor variant of the core app |
| `lib`/`app` split, `lib` has zero Android SDK dependencies | Keeps the brain logic unit-testable on the JVM in milliseconds (no emulator, no Robolectric), and makes it obvious at build time — not just by convention — when device-specific code has crept into the wrong place |

---

## Project structure

Two Gradle modules, matching the [`lib`/`app` split](#two-layers-lib-brain-and-app-android) above:

```
lib/                        # Kotlin/JVM module — NO Android SDK dependency
  src/main/kotlin/.../
    cmp/                     # CmpMessage, CmpGoal — the shared message format
    sensor/                  # SensorModule interface, RawTouchObservation,
                              # resampling/normalization (pure math, no MotionEvent)
    lm/
      LearningModule.kt       # shared interface
      PrimitiveLM.kt          # Tier 1
      CharacterGraphLM.kt     # Tier 2
    memory/
      GraphObjectModel.kt
      GraphMemory.kt          # merge/spawn logic, stateDict()/loadStateDict()
    orchestrator/
      MontyOrchestrator.kt
  src/test/kotlin/.../        # plain JUnit unit tests: resampling, segmentation,
                              # matching, merge/spawn, orchestrator step loop —
                              # runs on the JVM, no emulator, no Robolectric

app/                         # Android application module, depends on :lib
  src/main/kotlin/.../
    sensor/                  # MotionEvent -> RawTouchObservation adapter
                              # (buffering by pointer down/up; thin, no recognition logic)
    persistence/             # writes/reads lib's stateDict() bytes to Android storage
    ui/
      DrawingCanvas.kt
      TeachRecognizeScreen.kt
      DisambiguationDialog.kt
    MainActivity.kt          # wires MontyOrchestrator + adapters together
  src/androidTest/.../        # adapter + Compose UI smoke tests only

docs/
  IMPLEMENTATION_PLAN.md      # phased build plan, full interface code, mapping table
  README.md                   # this file
```

---

## Status / Roadmap

See **[`IMPLEMENTATION_PLAN.md`](./IMPLEMENTATION_PLAN.md)** for the full
phase-by-phase plan (Phases 0–7 for the touchscreen app, Phase 8 for the
static-image/multi-LM-voting stretch goal) and testing strategy.

**Not yet built** — this is a from-scratch project plan, not a working app.
Start at Phase 0.

---

## Known limitations (by design, not oversight)

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
  `GraphMemory.matchScore` — https://depts.washington.edu/acelab/proj/dollar/index.html
- Handwriting trajectory recovery / offline-to-online conversion literature —
  relevant background for the Phase 8 stretch goal.
