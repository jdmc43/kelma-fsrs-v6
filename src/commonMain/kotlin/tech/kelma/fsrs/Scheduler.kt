package tech.kelma.fsrs

import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class Scheduler(configuration: SchedulerConfig) {
    val config: SchedulerConfig = configuration.validatedCopy()
    private val algorithm = Algorithm(config.parameters)

    fun retrievability(card: Card, atMillis: Long): Double {
        val lastReview = card.lastReviewAtMillis ?: return 0.0
        val stability = card.stability ?: return 0.0
        return algorithm.retrievability(elapsedDays(lastReview, atMillis), stability)
    }

    fun review(card: Card, rating: Rating, reviewedAtMillis: Long): Card {
        val daysSinceLast = card.lastReviewAtMillis?.let { elapsedDays(it, reviewedAtMillis) }
        val memoryUpdated = when (card.state) {
            State.Learning -> updateLearningMemory(card, rating, daysSinceLast, reviewedAtMillis)
            State.Review -> updateReviewMemory(card, rating, daysSinceLast, reviewedAtMillis)
            State.Relearning -> updateRelearningMemory(card, rating, daysSinceLast, reviewedAtMillis)
        }
        val transition = when (memoryUpdated.state) {
            State.Learning -> transitionLearning(memoryUpdated, rating, config.learningSteps)
            State.Review -> transitionReview(memoryUpdated, rating)
            State.Relearning -> transitionLearning(memoryUpdated, rating, config.relearningSteps)
        }
        return transition.card.copy(
            dueAtMillis = reviewedAtMillis.safelyPlus(transition.interval.inWholeMilliseconds),
            lastReviewAtMillis = reviewedAtMillis,
        )
    }

    private fun updateLearningMemory(
        card: Card,
        rating: Rating,
        daysSinceLast: Int?,
        reviewedAtMillis: Long,
    ): Card {
        check(card.step != null) { "Learning card has no step" }
        if (card.stability == null || card.difficulty == null) {
            return card.copy(
                stability = algorithm.initialStability(rating),
                difficulty = algorithm.initialDifficulty(rating, clamp = true),
            )
        }
        return updateExistingMemory(card, rating, daysSinceLast, reviewedAtMillis)
    }

    private fun updateReviewMemory(
        card: Card,
        rating: Rating,
        daysSinceLast: Int?,
        reviewedAtMillis: Long,
    ): Card {
        check(card.stability != null && card.difficulty != null) {
            "Review card has incomplete memory state"
        }
        return updateExistingMemory(card, rating, daysSinceLast, reviewedAtMillis)
    }

    private fun updateRelearningMemory(
        card: Card,
        rating: Rating,
        daysSinceLast: Int?,
        reviewedAtMillis: Long,
    ): Card {
        check(card.step != null && card.stability != null && card.difficulty != null) {
            "Relearning card has incomplete state"
        }
        return updateExistingMemory(card, rating, daysSinceLast, reviewedAtMillis)
    }

    private fun updateExistingMemory(
        card: Card,
        rating: Rating,
        daysSinceLast: Int?,
        reviewedAtMillis: Long,
    ): Card {
        val stability = checkNotNull(card.stability) { "Card has no stability" }
        val difficulty = checkNotNull(card.difficulty) { "Card has no difficulty" }
        val nextStability = if (daysSinceLast != null && daysSinceLast < 1) {
            algorithm.shortTermStability(stability, rating)
        } else {
            algorithm.nextStability(
                difficulty = difficulty,
                stability = stability,
                retrievability = retrievability(card, reviewedAtMillis),
                rating = rating,
            )
        }
        return card.copy(
            stability = nextStability,
            difficulty = algorithm.nextDifficulty(difficulty, rating),
        )
    }

    private fun transitionLearning(
        card: Card,
        rating: Rating,
        steps: List<Duration>,
    ): Transition {
        val step = checkNotNull(card.step) { "Learning transition requires a step" }
        checkNotNull(card.stability) { "Learning transition requires stability" }
        check(step >= 0) { "Learning step cannot be negative" }
        if (steps.isEmpty() || (step >= steps.size && rating != Rating.Again)) {
            return graduate(card)
        }
        return when (rating) {
            Rating.Again -> Transition(card.copy(step = 0), steps[0])
            Rating.Hard -> when {
                step == 0 && steps.size == 1 -> Transition(card, steps[0] * 3 / 2)
                step == 0 && steps.size >= 2 -> Transition(card, (steps[0] + steps[1]) / 2)
                step < steps.size -> Transition(card, steps[step])
                else -> error("Learning step $step outside ${steps.size} configured steps")
            }
            Rating.Good -> when {
                step + 1 == steps.size -> graduate(card)
                step + 1 > steps.size ->
                    error("Learning step $step outside ${steps.size} configured steps")
                else -> Transition(card.copy(step = step + 1), steps[step + 1])
            }
            Rating.Easy -> graduate(card)
        }
    }

    private fun transitionReview(card: Card, rating: Rating): Transition {
        val stability = checkNotNull(card.stability) { "Review transition requires stability" }
        if (rating == Rating.Again && config.relearningSteps.isNotEmpty()) {
            return Transition(
                card = card.copy(state = State.Relearning, step = 0),
                interval = config.relearningSteps[0],
            )
        }
        return Transition(card.copy(step = null), reviewInterval(stability))
    }

    private fun graduate(card: Card): Transition {
        val stability = checkNotNull(card.stability) { "Graduation requires stability" }
        return Transition(
            card = card.copy(state = State.Review, step = null),
            interval = reviewInterval(stability),
        )
    }

    private fun reviewInterval(stability: Double): Duration =
        algorithm.nextInterval(
            stability = stability,
            desiredRetention = config.desiredRetention,
            maximum = config.maximumIntervalDays,
        ).days

    private data class Transition(val card: Card, val interval: Duration)

    companion object {
        const val MillisPerDay: Long = 86_400_000L

        fun elapsedDays(fromMillis: Long, toMillis: Long): Int =
            floor((toMillis.toDouble() - fromMillis.toDouble()) / MillisPerDay.toDouble()).toInt()

        private fun Long.safelyPlus(positiveDelta: Long): Long =
            if (positiveDelta > 0L && this > Long.MAX_VALUE - positiveDelta) Long.MAX_VALUE
            else this + positiveDelta
    }
}
