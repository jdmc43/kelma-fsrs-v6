package tech.kelma.fsrs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

val DefaultParameters: List<Double> = listOf(
    0.212, 1.2931, 2.3065, 8.2956,
    6.4133, 0.8334, 3.0194, 0.001,
    1.8722, 0.1666, 0.796, 1.4835,
    0.0614, 0.2629, 1.6483, 0.6014,
    1.8729, 0.5425, 0.0912, 0.0658,
    0.1542,
)

val ParameterLowerBounds: List<Double> = listOf(
    0.001, 0.001, 0.001, 0.001,
    1.0, 0.001, 0.001, 0.001,
    0.0, 0.0, 0.001, 0.001,
    0.001, 0.001, 0.0, 0.0,
    1.0, 0.0, 0.0, 0.0,
    0.1,
)

val ParameterUpperBounds: List<Double> = listOf(
    100.0, 100.0, 100.0, 100.0,
    10.0, 4.0, 4.0, 0.75,
    4.5, 0.8, 3.5, 5.0,
    0.25, 0.9, 4.0, 1.0,
    6.0, 2.0, 2.0, 0.8,
    0.8,
)

data class SchedulerConfig(
    val parameters: List<Double>,
    val desiredRetention: Double,
    val learningSteps: List<Duration>,
    val relearningSteps: List<Duration>,
    val maximumIntervalDays: Int,
    val enableShortTerm: Boolean = true,
    val enableFuzzing: Boolean = false,
) {
    companion object {
        fun default(): SchedulerConfig = SchedulerConfig(
            parameters = DefaultParameters.toList(),
            desiredRetention = 0.90,
            learningSteps = listOf(1.minutes, 10.minutes),
            relearningSteps = listOf(10.minutes),
            maximumIntervalDays = 36_500,
            enableShortTerm = true,
            enableFuzzing = false,
        )
    }

    internal fun validatedCopy(): SchedulerConfig {
        if (parameters.size != 21) {
            invalid("expected 21 parameters, got ${parameters.size}")
        }
        parameters.forEachIndexed { index, value ->
            if (!value.isFinite() || value !in ParameterLowerBounds[index]..ParameterUpperBounds[index]) {
                invalid(
                    "parameter[$index]=$value outside " +
                        "[${ParameterLowerBounds[index]},${ParameterUpperBounds[index]}]",
                )
            }
        }
        if (!desiredRetention.isFinite() || desiredRetention <= 0.0 || desiredRetention > 1.0) {
            invalid("desired retention must be in (0,1]")
        }
        if (maximumIntervalDays !in 1..36_500) {
            invalid("maximum interval must be in [1,36500]")
        }
        if (!enableShortTerm) {
            invalid("short-term scheduling must be enabled")
        }
        if (enableFuzzing) {
            invalid("deterministic scheduling does not support fuzzing")
        }
        validateSteps("learning", learningSteps)
        validateSteps("relearning", relearningSteps)
        return copy(
            parameters = parameters.toList(),
            learningSteps = learningSteps.toList(),
            relearningSteps = relearningSteps.toList(),
        )
    }
}

private fun validateSteps(name: String, steps: List<Duration>) {
    steps.forEachIndexed { index, step ->
        if (!step.isFinite() || step <= Duration.ZERO || step >= 1.days) {
            invalid("$name step[$index] must be between zero and one day")
        }
        if (index > 0 && step <= steps[index - 1]) {
            invalid("$name steps must be strictly increasing")
        }
    }
}

private fun invalid(message: String): Nothing = throw InvalidConfigurationException(message)
