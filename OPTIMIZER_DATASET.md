# Optimizer dataset contract

This document defines the immutable-history input and deterministic development
oracle for the `kelma-fsrs-v6` Kotlin Multiplatform optimizer. Scheduling,
replay, dataset formatting, parameter initialization, and local training are
implemented in `commonMain`.

## Authority and scope

`fsrs-optimizer==6.5.0` is the development oracle for parameter initialization,
training, parameter bounds, and loss metrics. It is not a runtime dependency.
The committed fixture is generated only in a pinned `linux/amd64` container.

The Kotlin implementation consumes this contract rather than CSV, pandas,
PyTorch, Python timestamps, or package-internal dataframes. No application code
may invoke Python or send review history to KelmaSync for training.

## Immutable review input

Each optimizer review contains only:

```text
reviewId: immutable signed 64-bit ID
cardIdentity: portable logical card identity
reviewedAtMillis: UTC Unix epoch milliseconds
rating: Again=1, Hard=2, Good=3, Easy=4
state: optional origin state, New=0, Learning=1, Review=2, Relearning=3
durationMillis: optional non-negative answer duration
```

Card text, note fields, templates, tags, deck names, media, source due dates,
intervals, factors, credentials, and passwords are excluded.

Reviews are grouped by `cardIdentity` and ordered by `reviewedAtMillis`, then
`reviewId`. Duplicate IDs follow the scheduler replay contract: identical rows
are idempotent and conflicting rows are invalid. The oracle inputs contain no
collisions.

## Study days and elapsed days

Optimizer elapsed days intentionally differ from scheduler elapsed days:

- Scheduler replay uses completed 24-hour periods.
- Optimizer formatting uses user study-day boundaries.

For each review, convert the UTC timestamp to the configured IANA timezone,
subtract `dayStartHour` as an elapsed duration, and take the resulting local
calendar date. Elapsed days are differences between consecutive study-day
ordinals for one card. This preserves the selected `fsrs-optimizer` behavior at
DST transitions; it is not equivalent to subtracting from a timezone-naive
clock.

The fixture includes UTC 04:00 boundaries and New York spring/fall DST vectors.
Timezone and day-start policy are frozen into every optimization snapshot.

## Training examples

The package-compatible formatter retains same-day ratings in prior history but
excludes a review with elapsed day zero as a prediction target. The first
remaining cross-day row for each card is initialization context and is not an
optimizer example. Package `6.5.0` outlier and continuity filtering is part of
the pinned fixture behavior.

Each final example contains:

```text
reviewId and cardIdentity
reviewedAtMillis
rating and recalled outcome
elapsedDays for the target
historyElapsedDays and historyRatings
cross-day sequence index
lastCrossDayRating
```

Again is a forgotten outcome. Hard, Good, and Easy are recalled outcomes.
Fixture `datasetSha256` values hash canonical JSON with sorted keys and no
insignificant whitespace.

## Eligibility

Initial product eligibility requires all of:

- at least 1,000 qualifying examples;
- at least 100 cards represented by qualifying examples;
- at least one recalled outcome;
- at least one forgotten outcome.

Failure codes are stable:

```text
insufficient_reviews
insufficient_cards
missing_recalled_outcome
missing_forgotten_outcome
```

Insufficient input returns no candidate and never substitutes default
parameters while claiming optimization succeeded.

## History hash

The library fixture uses SHA-256 over rows globally ordered by `reviewId`, then
`cardIdentity`. Each row is UTF-8:

```text
reviewId + "\n" +
UTF8_byte_length(cardIdentity) + ":" + cardIdentity + "\n" +
reviewedAtMillis + "\n" +
rating + "\n" +
state + "\n" +
durationMillis + "\n"
```

A missing origin state is normalized to `-1`; missing duration is normalized to
`0` before hashing and formatting.

The application additionally computes KelmaSync's server-defined optimization
history hash over review checksums when publishing an optimized cloud profile.
The two hashes serve different identities and must not be conflated.

## Pinned training run

The successful fixture uses:

- short-term scheduling enabled;
- integer study-day intervals;
- deterministic CPU operations and one computation thread;
- seed `2023`;
- portable LCG32/Fisher–Yates batch shuffling with multiplier `1664525` and
  increment `1013904223`;
- chronological 80/20 training/validation split;
- three epochs;
- learning rate `0.04`;
- regularization gamma `1.0`;
- batch size `128`;
- maximum sequence length `64`;
- the oracle's cubic recency weights;
- output parameters rounded to four decimals;
- log-loss metrics rounded to six decimals so nanounit CPU-reduction differences
  do not change canonical fixture bytes.

The fixture records initialized and optimized 21-parameter vectors, unregularized
weighted binary log loss before/after for training and validation, parameter
bounds, and an identical repeated run. These settings define this fixture, not
a promise that every future product optimization must use only three epochs.
Changing training policy requires a new fixture schema/version. The portable
shuffle is intentional: it avoids exposing production behavior to PyTorch's
internal random-permutation implementation while the Python oracle still
executes the pinned optimizer model, loss, Adam updates, and parameter clipping.

## Cancellation contract

Cancellation is checked:

```text
before_formatting
after_formatting
between_epochs
before_candidate_publication
```

At every checkpoint, cancellation yields status `cancelled`, no candidate, and
no active-parameter change. Partial optimizer weights are never eligible for
Apply or cloud publication. This cancellation behavior is a Kelma contract;
the Python package itself does not provide the application lifecycle API.

## Privacy and real-history validation

Committed fixtures are synthetic. Real collections are processed only by the
local opt-in acceptance tools, which emit aggregate counts and temporary
schedule-only data. Real card/review identifiers and review histories are never
committed. Temporary optimizer datasets must be deleted after success,
cancellation, process recovery, sign-out, or account change.
