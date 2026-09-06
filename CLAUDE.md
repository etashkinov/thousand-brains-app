# Project notes

- The real Monty reference implementation is checked out locally at
  `/Users/gtashkinov/Repositories/tbp.monty/`. Consult it whenever a design
  question is about whether/how this port matches Monty's actual behavior
  (e.g. `src/tbp/monty/cmp.py` for the CMP `Message`/`Goal` classes,
  `src/tbp/monty/frameworks/models/evidence_matching/learning_module.py` for
  a real `LearningModule`'s `get_output()`/`send_out_vote()`/`receive_votes()`)
  — prefer reading it over recalling/guessing Monty's design from memory.

## Design rule: default to Monty's own approach

This app should be easily understandable to anyone already familiar with
Monty. When a design decision is ambiguous or underspecified by this
project's own docs, don't invent a new approach — check what real Monty
actually does (see above) and follow it, even if a different design seems
locally cleaner. Diverging from Monty's base design is fine when there's a
concrete, stated reason this app's domain requires it (e.g. touchscreen 2D
vs. simulated 3D touch/vision), but should be a deliberate, documented
exception, not a default.

## Never run adb tests against a connected device

Do not use `adb` (install/launch/shell input/screencap/logcat or otherwise)
to manually exercise the app on any device connected to this machine, even
to "just verify" a change. Any `adb devices` entry seen from this machine
may be the user's own personal phone (connected via wireless debugging),
not a dedicated/idle test device — it can be actively in the user's hand
running unrelated apps, and synthetic taps/swipes sent to it land on
whatever is actually on screen, with real side effects outside this
project. This already happened once: a verification pass sent touch input
that landed in the user's social media feed mid-session.

Rely on the `lib` module's unit tests (`./gradlew :lib:test`) for
verification instead — the module boundary (§2a in
`IMPLEMENTATION_PLAN.md`) exists specifically so the recognition logic is
fully testable on the JVM without a device. For `app`-side/UI changes that
unit tests can't cover, say explicitly that on-device verification wasn't
performed and let the user test it themselves, rather than driving a
connected device.
