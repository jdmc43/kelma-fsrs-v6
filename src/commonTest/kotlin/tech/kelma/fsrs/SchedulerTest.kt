package tech.kelma.fsrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

class SchedulerTest {
    private val base = 1_735_689_600_000L

    @Test
    fun reviewDoesNotMutateInput() {
        val scheduler = Scheduler(SchedulerConfig.default())
        val original = Card.new(id = 7, dueAtMillis = base)
        val next = scheduler.review(original, Rating.Good, base)
        assertNull(original.stability)
        assertNull(original.difficulty)
        assertNull(original.lastReviewAtMillis)
        assertNotNull(next.stability)
        assertNotNull(next.difficulty)
        assertEquals(base, next.lastReviewAtMillis)
    }

    @Test
    fun replayOrdersAndDeduplicatesImmutableReviews() {
        val scheduler = Scheduler(SchedulerConfig.default())
        val first = ReviewEvent(10, 7, Rating.Good, base)
        val second = ReviewEvent(11, 7, Rating.Good, base + 10.minutes.inWholeMilliseconds)
        val results = scheduler.replay(Card.new(7, base), listOf(second, first, first))
        assertEquals(listOf(10L, 11L), results.map { it.event.id })
    }

    @Test
    fun replayRejectsConflictingDuplicateId() {
        val scheduler = Scheduler(SchedulerConfig.default())
        assertFailsWith<ReviewCollisionException> {
            scheduler.replay(
                Card.new(7, base),
                listOf(
                    ReviewEvent(10, 7, Rating.Good, base),
                    ReviewEvent(10, 7, Rating.Again, base),
                ),
            )
        }
    }

    @Test
    fun configurationValidationRejectsUnsupportedInputs() {
        assertFailsWith<InvalidConfigurationException> {
            Scheduler(SchedulerConfig.default().copy(parameters = DefaultParameters.take(19)))
        }
        assertFailsWith<InvalidConfigurationException> {
            Scheduler(SchedulerConfig.default().copy(desiredRetention = 0.0))
        }
        assertFailsWith<InvalidConfigurationException> {
            Scheduler(SchedulerConfig.default().copy(maximumIntervalDays = 36_501))
        }
        assertFailsWith<InvalidConfigurationException> {
            Scheduler(SchedulerConfig.default().copy(enableFuzzing = true))
        }
        assertFailsWith<InvalidConfigurationException> {
            Scheduler(
                SchedulerConfig.default().copy(
                    learningSteps = listOf(10.minutes, 1.minutes),
                ),
            )
        }
    }

    @Test
    fun dueTimestampSaturatesInsteadOfOverflowing() {
        val result = Scheduler(SchedulerConfig.default()).review(
            Card.new(7, Long.MAX_VALUE - 1_000),
            Rating.Easy,
            Long.MAX_VALUE - 1_000,
        )
        assertEquals(Long.MAX_VALUE, result.dueAtMillis)
    }

    @Test
    fun elapsedDaysUsesCompletedTwentyFourHourPeriods() {
        assertEquals(0, Scheduler.elapsedDays(base, base + 23 * 60 * 60 * 1000L))
        assertEquals(1, Scheduler.elapsedDays(base, base + 24 * 60 * 60 * 1000L))
        assertEquals(-1, Scheduler.elapsedDays(base, base - 1))
    }
}
