package tech.kelma.fsrs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

private const val MinimumStability = 0.001

internal class Algorithm(parameters: List<Double>) {
    private val weights = parameters.toList()
    private val decay = -weights[20]
    private val factor = 0.9.pow(1.0 / decay) - 1.0

    fun retrievability(elapsedDays: Int, stability: Double): Double =
        (1.0 + factor * max(elapsedDays, 0).toDouble() / stability).pow(decay)

    fun initialStability(rating: Rating): Double =
        max(weights[rating.value - 1], MinimumStability)

    fun initialDifficulty(rating: Rating, clamp: Boolean): Double {
        val value = weights[4] - exp(weights[5] * (rating.value - 1).toDouble()) + 1.0
        return if (clamp) clampDifficulty(value) else value
    }

    fun nextInterval(stability: Double, desiredRetention: Double, maximum: Int): Int {
        val value = stability / factor * (desiredRetention.pow(1.0 / decay) - 1.0)
        return round(value).coerceIn(1.0, maximum.toDouble()).toInt()
    }

    fun shortTermStability(stability: Double, rating: Rating): Double {
        var increase = exp(weights[17] * (rating.value.toDouble() - 3.0 + weights[18])) *
            stability.pow(-weights[19])
        if (rating == Rating.Good || rating == Rating.Easy) {
            increase = max(increase, 1.0)
        }
        return max(stability * increase, MinimumStability)
    }

    fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val delta = -(weights[6] * (rating.value.toDouble() - 3.0))
        val damped = difficulty + (10.0 - difficulty) * delta / 9.0
        val target = initialDifficulty(Rating.Easy, clamp = false)
        return clampDifficulty(weights[7] * target + (1.0 - weights[7]) * damped)
    }

    fun nextStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Rating,
    ): Double {
        val result = if (rating == Rating.Again) {
            val longTerm = weights[11] *
                difficulty.pow(-weights[12]) *
                ((stability + 1.0).pow(weights[13]) - 1.0) *
                exp((1.0 - retrievability) * weights[14])
            val shortTerm = stability / exp(weights[17] * weights[18])
            min(longTerm, shortTerm)
        } else {
            val hardPenalty = if (rating == Rating.Hard) weights[15] else 1.0
            val easyBonus = if (rating == Rating.Easy) weights[16] else 1.0
            stability * (
                1.0 + exp(weights[8]) *
                    (11.0 - difficulty) *
                    stability.pow(-weights[9]) *
                    (exp((1.0 - retrievability) * weights[10]) - 1.0) *
                    hardPenalty * easyBonus
                )
        }
        return max(result, MinimumStability)
    }

    private fun clampDifficulty(value: Double): Double = value.coerceIn(1.0, 10.0)
}
