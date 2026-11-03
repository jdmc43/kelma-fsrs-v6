# Upstream provenance

`kelma-fsrs-v6` is an independent Kotlin Multiplatform adaptation of
permissively licensed FSRS references. It contains no Anki/rslib implementation
code.

## Scheduling implementation reference

- Project: `open-spaced-repetition/go-fsrs`
- Version: `v4.0.0`
- Commit: `61159b9b3891b1279b63206afef4086d7ef92992`
- License: MIT
- Copyright: 2022 open-spaced-repetition

The Kotlin scheduler follows the formulas, parameter bounds, state transitions,
and learning-step behavior adapted earlier for KelmaSync's internal Go
scheduler. Kotlin models, millisecond time representation, immutable API, replay
surface, validation, and tests were written for this library.

## Canonical behavior oracle

- Package: `py-fsrs==6.3.1`
- Release commit: `3abe686e9c058d3f3c00bbeb92e68b71211b2b31`
- License: MIT
- Wheel SHA-256:
  `ac1bf9939573592d8c9bc1e11a00bd17e04146dc9f2c913127e2bcc431b9040b`
- Container: `python:3.13.12-slim`
- Container digest:
  `sha256:f1927c75e81efd1e091dbd64b6c0ecaa5630b38635a3d1c04034ac636e1f94c8`
- Canonical platform: `linux/amd64`

Python is a fixture oracle only and is not linked, bundled, or executed by the
library.

## Optimizer oracle

- Project: `open-spaced-repetition/fsrs-optimizer`
- Package: `FSRS-Optimizer==6.5.0`
- Release commit: `cf052b7843452369485f9d39f9ada562ba152ea8`
- License: BSD-3-Clause
- Copyright: 2023 Open Spaced Repetition
- Wheel SHA-256:
  `245ea144584c3db2bbdfdccb3beebf29978a400aa01f8cd33bf947b6f7cff20c`
- PyTorch: `2.9.1+cpu`
- Container: `python:3.13.12-slim`
- Container digest:
  `sha256:f1927c75e81efd1e091dbd64b6c0ecaa5630b38635a3d1c04034ac636e1f94c8`
- Canonical platform: `linux/amd64`

The optimizer package, PyTorch, pandas, NumPy, SciPy, and related locked
packages are development-only fixture tooling. The Kotlin implementation is an
independent `commonMain` adaptation of the documented FSRS formulas, parameter
initialization, regularized loss, Adam updates, and bounds; no Python source or
runtime is bundled. Generated synthetic inputs, initialized and optimized
parameter vectors, batch orders, and loss metrics define the behavioral oracle.

## Intentional compatibility choices

- Elapsed days are completed 24-hour periods, not fractional days or UTC
  calendar boundaries.
- Interval rounding uses ties-to-even behavior.
- Immutable reviews are replayed by timestamp and then review ID.
- Identical duplicate IDs are idempotent; conflicting duplicates fail.
- Fuzzing is rejected by this deterministic release.
- Public time values are epoch milliseconds; learning-step configuration uses
  common Kotlin `Duration` values.
- Optimizer study days use an explicit IANA timezone and local day-start hour.
- Optimizer batches use a specified portable LCG32/Fisher–Yates shuffle instead
  of depending on PyTorch's platform-specific random permutation internals.
