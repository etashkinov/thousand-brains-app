# Project notes

- The real Monty reference implementation is checked out locally at
  `/Users/gtashkinov/Repositories/tbp.monty/`. Consult it whenever a design
  question is about whether/how this port matches Monty's actual behavior
  (e.g. `src/tbp/monty/cmp.py` for the CMP `Message`/`Goal` classes,
  `src/tbp/monty/frameworks/models/evidence_matching/learning_module.py` for
  a real `LearningModule`'s `get_output()`/`send_out_vote()`/`receive_votes()`)
  — prefer reading it over recalling/guessing Monty's design from memory.
