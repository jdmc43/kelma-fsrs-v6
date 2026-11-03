package tech.kelma.fsrs

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

internal object OptimizerInitialization {
    fun initialize(dataset: OptimizerDataset): List<Double> {
        require(dataset.examples.isNotEmpty()) { "optimizer dataset is empty" }
        val averageRecall = dataset.examples.count { it.recalled }.toDouble() /
            dataset.examples.size
        val stability = mutableMapOf<Int, Double>()
        val count = mutableMapOf<Int, Int>()
        for (rating in Rating.entries) {
            val rows = dataset.initializationRows.filter { it.firstRating == rating }
            if (rows.isEmpty()) continue
            val initial = DefaultParameters[rating.value - 1]
            stability[rating.value] = fitStability(rows, averageRecall, initial)
            count[rating.value] = rows.sumOf { it.count }
        }
        enforceMonotonicity(stability, count)
        val initialized = fillMissingStabilities(stability)
            .map { it.coerceIn(0.001, 100.0) }
        return initialized + DefaultParameters.drop(4)
    }

    private fun fitStability(
        rows: List<OptimizerInitializationRow>,
        averageRecall: Double,
        initial: Double,
    ): Double {
        fun objective(stability: Double): Double {
            var loss = abs(stability - initial) / 16.0
            for (row in rows) {
                val recall = (row.recalledRate * row.count + averageRecall) /
                    (row.count + 1.0)
                val prediction = forgettingCurve(
                    elapsedDays = row.elapsedDays.toDouble(),
                    stability = stability,
                    decay = -DefaultParameters[20],
                ).coerceIn(1e-15, 1.0 - 1e-15)
                loss += -(
                    recall * ln(prediction) +
                        (1.0 - recall) * ln(1.0 - prediction)
                    ) * row.count
            }
            return loss
        }

        var left = 0.001
        var right = 100.0
        val ratio = (sqrt(5.0) - 1.0) / 2.0
        var low = right - ratio * (right - left)
        var high = left + ratio * (right - left)
        var lowLoss = objective(low)
        var highLoss = objective(high)
        repeat(256) {
            if (lowLoss < highLoss) {
                right = high
                high = low
                highLoss = lowLoss
                low = right - ratio * (right - left)
                lowLoss = objective(low)
            } else {
                left = low
                low = high
                lowLoss = highLoss
                high = left + ratio * (right - left)
                highLoss = objective(high)
            }
        }
        return listOf((left + right) / 2.0, initial, 0.001, 100.0)
            .minBy { objective(it) }
    }

    private fun enforceMonotonicity(
        stability: MutableMap<Int, Double>,
        count: Map<Int, Int>,
    ) {
        val comparisons = listOf(1 to 2, 2 to 3, 3 to 4, 1 to 3, 2 to 4, 1 to 4)
        comparisons.forEach { (small, big) ->
            val smallValue = stability[small]
            val bigValue = stability[big]
            if (smallValue != null && bigValue != null && smallValue > bigValue) {
                if (count.getValue(small) > count.getValue(big)) {
                    stability[big] = smallValue
                } else {
                    stability[small] = bigValue
                }
            }
        }
    }

    private fun fillMissingStabilities(values: MutableMap<Int, Double>): List<Double> {
        val defaults = (1..4).associateWith { DefaultParameters[it - 1] }
        val w1 = 0.41
        val w2 = 0.54
        when (values.size) {
            0 -> throw IllegalArgumentException("not enough data for parameter initialization")
            1 -> {
                val rating = values.keys.single()
                val factor = values.getValue(rating) / defaults.getValue(rating)
                return (1..4).map { defaults.getValue(it) * factor }
            }
            2 -> fillTwo(values, w1, w2)
            3 -> when {
                1 !in values -> values[1] = geometric(values.getValue(2), 1.0 / w1, values.getValue(3), 1.0 - 1.0 / w1)
                2 !in values -> values[2] = geometric(values.getValue(1), w1, values.getValue(3), 1.0 - w1)
                3 !in values -> values[3] = geometric(values.getValue(2), 1.0 - w2, values.getValue(4), w2)
                4 !in values -> values[4] = geometric(values.getValue(2), 1.0 - 1.0 / w2, values.getValue(3), 1.0 / w2)
            }
        }
        return (1..4).map { values.getValue(it) }
    }

    private fun fillTwo(values: MutableMap<Int, Double>, w1: Double, w2: Double) {
        when {
            1 !in values && 2 !in values -> {
                values[2] = geometric(values.getValue(3), 1.0 / (1.0 - w2), values.getValue(4), 1.0 - 1.0 / (1.0 - w2))
                values[1] = geometric(values.getValue(2), 1.0 / w1, values.getValue(3), 1.0 - 1.0 / w1)
            }
            1 !in values && 3 !in values -> {
                values[3] = geometric(values.getValue(2), 1.0 - w2, values.getValue(4), w2)
                values[1] = geometric(values.getValue(2), 1.0 / w1, values.getValue(3), 1.0 - 1.0 / w1)
            }
            1 !in values && 4 !in values -> {
                values[4] = geometric(values.getValue(2), 1.0 - 1.0 / w2, values.getValue(3), 1.0 / w2)
                values[1] = geometric(values.getValue(2), 1.0 / w1, values.getValue(3), 1.0 - 1.0 / w1)
            }
            2 !in values && 3 !in values -> {
                val denominator = w1 + w2 - w1 * w2
                values[2] = geometric(values.getValue(1), w1 / denominator, values.getValue(4), 1.0 - w1 / denominator)
                values[3] = geometric(values.getValue(1), 1.0 - w2 / denominator, values.getValue(4), w2 / denominator)
            }
            2 !in values && 4 !in values -> {
                values[2] = geometric(values.getValue(1), w1, values.getValue(3), 1.0 - w1)
                values[4] = geometric(values.getValue(2), 1.0 - 1.0 / w2, values.getValue(3), 1.0 / w2)
            }
            3 !in values && 4 !in values -> {
                values[3] = geometric(values.getValue(1), 1.0 - 1.0 / (1.0 - w1), values.getValue(2), 1.0 / (1.0 - w1))
                values[4] = geometric(values.getValue(2), 1.0 - 1.0 / w2, values.getValue(3), 1.0 / w2)
            }
        }
    }

    private fun geometric(left: Double, leftPower: Double, right: Double, rightPower: Double): Double =
        left.pow(leftPower) * right.pow(rightPower)
}

internal fun forgettingCurve(elapsedDays: Double, stability: Double, decay: Double): Double {
    val factor = 0.9.pow(1.0 / decay) - 1.0
    return (1.0 + factor * elapsedDays / stability).pow(decay)
}
