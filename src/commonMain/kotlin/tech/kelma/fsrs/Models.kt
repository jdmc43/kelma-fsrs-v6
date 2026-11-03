package tech.kelma.fsrs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

enum class State {
    Learning,
    Review,
    Relearning,
}

enum class Rating(val value: Int) {
    Again(1),
    Hard(2),
    Good(3),
    Easy(4),
    ;

    companion object {
        fun fromValue(value: Int): Rating =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Invalid FSRS rating: $value")
    }
}

data class Card(
    val id: Long,
    val state: State,
    val step: Int?,
    val stability: Double?,
    val difficulty: Double?,
    val dueAtMillis: Long,
    val lastReviewAtMillis: Long?,
) {
    companion object {
        fun new(id: Long, dueAtMillis: Long): Card = Card(
            id = id,
            state = State.Learning,
            step = 0,
            stability = null,
            difficulty = null,
            dueAtMillis = dueAtMillis,
            lastReviewAtMillis = null,
        )
    }
}

data class ReviewEvent(
    val id: Long,
    val cardId: Long,
    val rating: Rating,
    val reviewedAtMillis: Long,
    val duration: Duration = ZERO,
)

data class ReviewResult(
    val card: Card,
    val event: ReviewEvent,
)

class InvalidConfigurationException(message: String) : IllegalArgumentException(message)

class ReviewCollisionException(val reviewId: Long) :
    IllegalArgumentException("Conflicting immutable reviews reuse ID $reviewId")
