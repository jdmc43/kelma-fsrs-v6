package tech.kelma.fsrs

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

internal const val OptimizerParameterCount = 21
private const val MinimumOptimizerStability = 0.001f

internal data class ValueAndGradient(
    val value: Float,
    val gradient: FloatArray,
) {
    operator fun plus(other: ValueAndGradient): ValueAndGradient = combine(other, value + other.value) { a, b -> a + b }
    operator fun minus(other: ValueAndGradient): ValueAndGradient = combine(other, value - other.value) { a, b -> a - b }

    operator fun times(other: ValueAndGradient): ValueAndGradient {
        val result = FloatArray(OptimizerParameterCount)
        for (index in result.indices) {
            result[index] = gradient[index] * other.value + other.gradient[index] * value
        }
        return ValueAndGradient(value * other.value, result)
    }

    operator fun div(other: ValueAndGradient): ValueAndGradient {
        val denominator = other.value * other.value
        val result = FloatArray(OptimizerParameterCount)
        for (index in result.indices) {
            result[index] = (
                gradient[index] * other.value - other.gradient[index] * value
                ) / denominator
        }
        return ValueAndGradient(value / other.value, result)
    }

    operator fun unaryMinus(): ValueAndGradient =
        ValueAndGradient(-value, FloatArray(OptimizerParameterCount) { -gradient[it] })

    fun exp(): ValueAndGradient {
        val output = exp(value)
        return ValueAndGradient(output, FloatArray(OptimizerParameterCount) { gradient[it] * output })
    }

    fun ln(): ValueAndGradient =
        ValueAndGradient(ln(value), FloatArray(OptimizerParameterCount) { gradient[it] / value })

    fun pow(exponent: ValueAndGradient): ValueAndGradient = (exponent * ln()).exp()

    fun clamp(minimum: Float, maximum: Float): ValueAndGradient = when {
        value < minimum -> constant(minimum)
        value > maximum -> constant(maximum)
        else -> this
    }

    private inline fun combine(
        other: ValueAndGradient,
        output: Float,
        operation: (Float, Float) -> Float,
    ): ValueAndGradient = ValueAndGradient(
        output,
        FloatArray(OptimizerParameterCount) { operation(gradient[it], other.gradient[it]) },
    )

    companion object {
        fun constant(value: Float): ValueAndGradient =
            ValueAndGradient(value, FloatArray(OptimizerParameterCount))

        fun parameter(value: Float, index: Int): ValueAndGradient =
            ValueAndGradient(value, FloatArray(OptimizerParameterCount) { if (it == index) 1f else 0f })
    }
}

internal operator fun ValueAndGradient.plus(value: Float): ValueAndGradient =
    this + ValueAndGradient.constant(value)

internal operator fun Float.plus(value: ValueAndGradient): ValueAndGradient =
    ValueAndGradient.constant(this) + value

internal operator fun ValueAndGradient.minus(value: Float): ValueAndGradient =
    this - ValueAndGradient.constant(value)

internal operator fun Float.minus(value: ValueAndGradient): ValueAndGradient =
    ValueAndGradient.constant(this) - value

internal operator fun ValueAndGradient.times(value: Float): ValueAndGradient =
    this * ValueAndGradient.constant(value)

internal operator fun Float.times(value: ValueAndGradient): ValueAndGradient = value * this

internal operator fun ValueAndGradient.div(value: Float): ValueAndGradient =
    this / ValueAndGradient.constant(value)

internal operator fun Float.div(value: ValueAndGradient): ValueAndGradient =
    ValueAndGradient.constant(this) / value

internal class DifferentiableFsrs(private val parameters: List<ValueAndGradient>) {
    fun retention(example: OptimizerExample): ValueAndGradient {
        var stability = ValueAndGradient.constant(0f)
        var difficulty = ValueAndGradient.constant(0f)
        example.historyRatings.indices.forEach { index ->
            val rating = example.historyRatings[index].value.toFloat()
            val elapsed = example.historyElapsedDays[index].toFloat()
            if (index == 0) {
                stability = parameters[example.historyRatings[index].value - 1]
                difficulty = initialDifficulty(rating).clamp(1f, 10f)
            } else {
                val retrievability = forgettingCurve(elapsed, stability)
                stability = if (elapsed < 1f) {
                    shortTermStability(stability, rating)
                } else if (rating > 1f) {
                    successStability(stability, difficulty, retrievability, rating)
                } else {
                    failureStability(stability, difficulty, retrievability)
                }
                difficulty = nextDifficulty(difficulty, rating)
            }
            stability = stability.clamp(MinimumOptimizerStability, 36_500f)
        }
        return forgettingCurve(example.elapsedDays.toFloat(), stability)
    }

    private fun forgettingCurve(
        elapsedDays: Float,
        stability: ValueAndGradient,
    ): ValueAndGradient {
        val decay = -parameters[20]
        val factor = ValueAndGradient.constant(0.9f).pow(1f / decay) - 1f
        return (1f + factor * elapsedDays / stability).pow(decay)
    }

    private fun initialDifficulty(rating: Float): ValueAndGradient =
        parameters[4] - (parameters[5] * (rating - 1f)).exp() + 1f

    private fun nextDifficulty(
        oldDifficulty: ValueAndGradient,
        rating: Float,
    ): ValueAndGradient {
        val delta = -parameters[6] * (rating - 3f)
        val damped = oldDifficulty + delta * (10f - oldDifficulty) / 9f
        val easy = initialDifficulty(4f)
        return (parameters[7] * easy + (1f - parameters[7]) * damped).clamp(1f, 10f)
    }

    private fun shortTermStability(
        oldStability: ValueAndGradient,
        rating: Float,
    ): ValueAndGradient {
        var increase = (parameters[17] * (rating - 3f + parameters[18])).exp() *
            oldStability.pow(-parameters[19])
        if (rating >= 2f && increase.value < 1f) {
            increase = ValueAndGradient.constant(1f)
        }
        return oldStability * increase
    }

    private fun successStability(
        oldStability: ValueAndGradient,
        difficulty: ValueAndGradient,
        retrievability: ValueAndGradient,
        rating: Float,
    ): ValueAndGradient {
        val hardPenalty = if (rating == 2f) parameters[15] else ValueAndGradient.constant(1f)
        val easyBonus = if (rating == 4f) parameters[16] else ValueAndGradient.constant(1f)
        return oldStability * (
            1f + parameters[8].exp() *
                (11f - difficulty) *
                oldStability.pow(-parameters[9]) *
                ((parameters[10] * (1f - retrievability)).exp() - 1f) *
                hardPenalty * easyBonus
            )
    }

    private fun failureStability(
        oldStability: ValueAndGradient,
        difficulty: ValueAndGradient,
        retrievability: ValueAndGradient,
    ): ValueAndGradient {
        val longTerm = parameters[11] *
            difficulty.pow(-parameters[12]) *
            ((oldStability + 1f).pow(parameters[13]) - 1f) *
            (parameters[14] * (1f - retrievability)).exp()
        val shortTerm = oldStability / (parameters[17] * parameters[18]).exp()
        return if (longTerm.value <= shortTerm.value) longTerm else shortTerm
    }
}

internal object FloatFsrs {
    fun retention(example: OptimizerExample, weights: FloatArray): Float =
        curve(example.elapsedDays.toFloat(), memoryStability(example, weights), weights[20])

    fun memoryStability(example: OptimizerExample, weights: FloatArray): Float {
        var stability = 0f
        var difficulty = 0f
        example.historyRatings.indices.forEach { index ->
            val rating = example.historyRatings[index].value.toFloat()
            val elapsed = example.historyElapsedDays[index].toFloat()
            if (index == 0) {
                stability = weights[example.historyRatings[index].value - 1]
                difficulty = initialDifficulty(weights, rating).coerceIn(1f, 10f)
            } else {
                val retrievability = curve(elapsed, stability, weights[20])
                stability = when {
                    elapsed < 1f -> shortTerm(weights, stability, rating)
                    rating > 1f -> success(weights, stability, difficulty, retrievability, rating)
                    else -> failure(weights, stability, difficulty, retrievability)
                }
                val delta = -weights[6] * (rating - 3f)
                val damped = difficulty + delta * (10f - difficulty) / 9f
                difficulty = (
                    weights[7] * initialDifficulty(weights, 4f) +
                        (1f - weights[7]) * damped
                    ).coerceIn(1f, 10f)
            }
            stability = stability.coerceIn(MinimumOptimizerStability, 36_500f)
        }
        return stability
    }

    private fun curve(elapsed: Float, stability: Float, decayWeight: Float): Float {
        val decay = -decayWeight
        val factor = 0.9f.pow(1f / decay) - 1f
        return (1f + factor * elapsed / stability).pow(decay)
    }

    private fun initialDifficulty(w: FloatArray, rating: Float): Float =
        w[4] - exp(w[5] * (rating - 1f)) + 1f

    private fun shortTerm(w: FloatArray, stability: Float, rating: Float): Float {
        var increase = exp(w[17] * (rating - 3f + w[18])) * stability.pow(-w[19])
        if (rating >= 2f) increase = maxOf(increase, 1f)
        return stability * increase
    }

    private fun success(w: FloatArray, s: Float, d: Float, r: Float, rating: Float): Float {
        val hard = if (rating == 2f) w[15] else 1f
        val easy = if (rating == 4f) w[16] else 1f
        return s * (
            1f + exp(w[8]) * (11f - d) * s.pow(-w[9]) *
                (exp((1f - r) * w[10]) - 1f) * hard * easy
            )
    }

    private fun failure(w: FloatArray, s: Float, d: Float, r: Float): Float {
        val longTerm = w[11] * d.pow(-w[12]) * ((s + 1f).pow(w[13]) - 1f) *
            exp((1f - r) * w[14])
        val shortTerm = s / exp(w[17] * w[18])
        return minOf(longTerm, shortTerm)
    }
}
