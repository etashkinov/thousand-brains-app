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
