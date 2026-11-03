# kelma-fsrs-v6

Independent Apache-2.0-licensed FSRS-6 scheduler and local parameter optimizer for
Kotlin Multiplatform.

Scheduling, immutable replay, dataset formatting, and optimization are
implemented in `commonMain`. The only production dependency is
`kotlinx-datetime` for study-day timezone handling; there is no Python, PyTorch,
Go, Compose, SQLDelight, Ktor, Anki, or rslib runtime dependency. Supported
targets are JVM, Android, iOS, macOS, and Linux.

## Behavioral contract

The scheduling behavior follows `py-fsrs==6.3.1`:

- 21 FSRS-6 parameters;
- completed 24-hour elapsed days;
- ties-to-even interval rounding;
- learning and relearning steps;
- short-term scheduling enabled;
- deterministic replay ordered by review timestamp and immutable review ID;
- identical duplicate reviews ignored and conflicting duplicate IDs rejected;
- fuzzing rejected until a separately validated deterministic contract exists.

The committed oracle contains 705 trajectories and has SHA-256:

```text
27c0777605a7ce95e1eaf38200bbe2e0b6daf41d8227486a6b4a84d427db8c1c
```

Python generates development fixtures only. It is not a library or application
runtime dependency.

## Usage

```kotlin
import tech.kelma.fsrs.Card
import tech.kelma.fsrs.Rating
import tech.kelma.fsrs.ReviewEvent
import tech.kelma.fsrs.Scheduler
import tech.kelma.fsrs.SchedulerConfig
import tech.kelma.fsrs.replay

val scheduler = Scheduler(SchedulerConfig.default())
val initial = Card.new(id = 42, dueAtMillis = firstReviewMillis)
val results = scheduler.replay(
    initial,
    listOf(
        ReviewEvent(
            id = firstReviewMillis,
            cardId = 42,
            rating = Rating.Good,
            reviewedAtMillis = firstReviewMillis,
        ),
    ),
)
val current = results.last().card
```

All models are immutable. Applications persist review history and derived card
state themselves; this library does not own queues, daily limits, ordering,
burying, sync, clocks, or UI.

## Verification

```bash
./gradlew jvmTest testAndroidHostTest linuxX64Test
./gradlew macosArm64Test iosSimulatorArm64Test
./tools/verify_optimizer_fixture.py
```

Regenerate the embedded common-test source after intentionally replacing the
pinned fixture:

```bash
./tools/embed_fixture.py
```

The generator refuses any fixture whose hash is not explicitly pinned.

## Local optimization

`FsrsOptimizer` formats content-free immutable reviews, checks eligibility,
initializes and trains all 21 parameters, reports progress, and returns an
unapplied candidate:

```kotlin
val result = FsrsOptimizer(
    StudyDayPolicy(timeZoneId = "America/New_York", dayStartHour = 4),
).optimize(reviews)

val candidate = (result as? OptimizationResult.CandidateReady)?.candidate
```

The default policy requires 1,000 qualifying cross-day reviews over 100 cards,
with both recalled and forgotten outcomes. Optimization is deterministic and
cancellable at defined checkpoints. It does not mutate active scheduler
settings or publish anything; applications must provide a separate reviewed
Apply action. See [OPTIMIZER_DATASET.md](OPTIMIZER_DATASET.md) and the pinned
[optimizer oracle](tools/optimizer_oracle/).

## License and provenance

Kelma code is licensed under Apache License 2.0. See [LICENSE](LICENSE) and
[NOTICE](NOTICE). Permissive upstream provenance and intentional behavioral
differences are documented in [UPSTREAM.md](UPSTREAM.md) and
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). No Anki/rslib or other AGPL
implementation code is included.
