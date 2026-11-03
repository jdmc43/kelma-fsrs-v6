package tech.kelma.fsrs

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

internal data class TrainingOutput(
    val parameters: List<Double>,
    val metrics: OptimizerMetrics,
    val batchOrders: List<List<Int>>,
)

internal class OptimizerTrainer(
    private val policy: OptimizerTrainingPolicy,
) {
    fun train(
        dataset: OptimizerDataset,
        initializedParameters: List<Double>,
        afterEpoch: (completedEpochs: Int) -> Boolean,
    ): TrainingOutput? {
        require(initializedParameters.size == OptimizerParameterCount)
        val weighted = addRecencyWeights(dataset.examples)
        val splitIndex = weighted.size * (100 - policy.validationPercent) / 100
        require(splitIndex in 1 until weighted.size) { "training/validation split is empty" }
        val chronologicalTrain = weighted.subList(0, splitIndex)
        val chronologicalValidation = weighted.subList(splitIndex, weighted.size)
        val train = chronologicalTrain
            .filter { it.example.historyRatings.size <= policy.maximumSequenceLength }
            .sortedBy { it.example.historyRatings.size }
        val validation = chronologicalValidation
            .filter { it.example.historyRatings.size <= policy.maximumSequenceLength }
            .sortedBy { it.example.historyRatings.size }
        require(train.isNotEmpty() && validation.isNotEmpty()) {
            "training data is inadequate for maximumSequenceLength"
        }
        val batches = train.chunked(policy.batchSize)
        val shuffle = PortableBatchShuffle(policy.seed)
        val weights = initializedParameters.map(Double::toFloat).toFloatArray()
        val initial = weights.copyOf()
        val firstMoment = FloatArray(OptimizerParameterCount)
        val secondMoment = FloatArray(OptimizerParameterCount)
        val batchOrders = mutableListOf<List<Int>>()
        var bestLoss = Float.POSITIVE_INFINITY
        var bestWeights: FloatArray? = null
        var step = 0
        val totalSteps = batches.size * policy.epochs

        repeat(policy.epochs) { epoch ->
            val evaluated = regularizedEvaluation(weights, initial, train, validation)
            if (evaluated < bestLoss) {
                bestLoss = evaluated
                bestWeights = weights.copyOf()
            }
            val order = shuffle.permutation(batches.size)
            batchOrders += order
            order.forEach { batchIndex ->
                val learningRate = (
                    policy.learningRate * 0.5 *
                        (1.0 + cos(PI * step.toDouble() / totalSteps.toDouble()))
                    ).toFloat()
                val gradient = batchGradient(
                    batch = batches[batchIndex],
                    parameters = weights,
                    initial = initial,
                    epochSize = train.size,
                )
                step += 1
                updateAdam(
                    parameters = weights,
                    gradient = gradient,
                    firstMoment = firstMoment,
                    secondMoment = secondMoment,
                    step = step,
                    learningRate = learningRate,
                )
                clipParameters(weights)
            }
            if (!afterEpoch(epoch + 1)) return null
        }

        val finalLoss = regularizedEvaluation(weights, initial, train, validation)
        if (finalLoss < bestLoss) {
            bestWeights = weights.copyOf()
        }
        val optimized = requireNotNull(bestWeights)
            .map { roundToDecimals(it.toDouble(), 4) }
        return TrainingOutput(
            parameters = optimized,
            metrics = OptimizerMetrics(
                trainingExamples = chronologicalTrain.size,
                validationExamples = chronologicalValidation.size,
                trainingLossBefore = roundToDecimals(
                    logLoss(chronologicalTrain, DefaultParameters),
                    6,
                ),
                trainingLossAfter = roundToDecimals(
                    logLoss(chronologicalTrain, optimized),
                    6,
                ),
                validationLossBefore = roundToDecimals(
                    logLoss(chronologicalValidation, DefaultParameters),
                    6,
                ),
                validationLossAfter = roundToDecimals(
                    logLoss(chronologicalValidation, optimized),
                    6,
                ),
            ),
            batchOrders = batchOrders,
        )
    }

    private fun batchGradient(
        batch: List<WeightedExample>,
        parameters: FloatArray,
        initial: FloatArray,
        epochSize: Int,
    ): FloatArray {
        val dualParameters = parameters.mapIndexed { index, value ->
            ValueAndGradient.parameter(value, index)
        }
        val model = DifferentiableFsrs(dualParameters)
        var loss = ValueAndGradient.constant(0f)
        batch.forEach { item ->
            val retention = model.retention(item.example)
            val binaryLoss = if (item.example.recalled) {
                -retention.ln()
            } else {
                -(1f - retention).ln()
            }
            loss += binaryLoss * item.weight.toFloat()
        }
        var penalty = ValueAndGradient.constant(0f)
        parameters.indices.forEach { index ->
            val difference = dualParameters[index] - ValueAndGradient.constant(initial[index])
            penalty += difference * difference /
                (ParameterStandardDeviations[index] * ParameterStandardDeviations[index])
        }
        loss += penalty * (
            policy.regularizationGamma.toFloat() * batch.size.toFloat() / epochSize.toFloat()
            )
        return loss.gradient
    }

    private fun regularizedEvaluation(
        parameters: FloatArray,
        initial: FloatArray,
        train: List<WeightedExample>,
        validation: List<WeightedExample>,
    ): Float {
        var penalty = 0f
        parameters.indices.forEach { index ->
            val difference = parameters[index] - initial[index]
            penalty += difference * difference /
                (ParameterStandardDeviations[index] * ParameterStandardDeviations[index])
        }
        val trainLoss = meanWeightedBinaryLoss(train, parameters) +
            penalty * policy.regularizationGamma.toFloat() / train.size
        val validationLoss = meanWeightedBinaryLoss(validation, parameters) +
            penalty * policy.regularizationGamma.toFloat() / train.size
        return (trainLoss * train.size + validationLoss * validation.size) /
            (train.size + validation.size)
    }

    private fun meanWeightedBinaryLoss(
        examples: List<WeightedExample>,
        parameters: FloatArray,
    ): Float {
        var sum = 0f
        examples.forEach { item ->
            val retention = FloatFsrs.retention(item.example, parameters)
            val loss = if (item.example.recalled) {
                -kotlin.math.ln(retention)
            } else {
                -kotlin.math.ln(1f - retention)
            }
            sum += loss * item.weight.toFloat()
        }
        return sum / examples.size
    }

    private fun logLoss(examples: List<WeightedExample>, parameters: List<Double>): Double {
        val floatParameters = parameters.map(Double::toFloat).toFloatArray()
        var weightedLoss = 0.0
        var totalWeight = 0.0
        examples.forEach { item ->
            val stability = FloatFsrs.memoryStability(item.example, floatParameters)
            val prediction = forgettingCurve(
                elapsedDays = item.example.elapsedDays.toDouble(),
                stability = stability.toDouble(),
                decay = -parameters[20],
            ).coerceIn(1e-7, 1.0 - 1e-7)
            val label = if (item.example.recalled) 1.0 else 0.0
            val loss = -(label * ln(prediction) + (1.0 - label) * ln(1.0 - prediction))
            weightedLoss += loss * item.weight
            totalWeight += item.weight
        }
        return weightedLoss / totalWeight
    }

    private fun updateAdam(
        parameters: FloatArray,
        gradient: FloatArray,
        firstMoment: FloatArray,
        secondMoment: FloatArray,
        step: Int,
        learningRate: Float,
    ) {
        val biasCorrection1 = 1.0 - 0.9.pow(step)
        val biasCorrection2Sqrt = sqrt(1.0 - 0.999.pow(step))
        val stepSize = learningRate.toDouble() / biasCorrection1
        parameters.indices.forEach { index ->
            firstMoment[index] += (gradient[index] - firstMoment[index]) * 0.1f
            secondMoment[index] = secondMoment[index] * 0.999f +
                gradient[index] * gradient[index] * 0.001f
            val denominator = secondMoment[index].toDouble().let(::sqrt) /
                biasCorrection2Sqrt + 1e-8
            parameters[index] = (
                parameters[index].toDouble() - stepSize * firstMoment[index] / denominator
                ).toFloat()
        }
    }

    private fun clipParameters(parameters: FloatArray) {
        parameters.indices.forEach { index ->
            parameters[index] = parameters[index].coerceIn(
                OptimizerParameterLowerBounds[index].toFloat(),
                OptimizerParameterUpperBounds[index].toFloat(),
            )
        }
    }

    private fun addRecencyWeights(examples: List<OptimizerExample>): List<WeightedExample> =
        examples.mapIndexed { index, example ->
            val position = if (examples.size == 1) 0.0 else {
                index.toDouble() / (examples.size - 1).toDouble()
            }
            WeightedExample(
                example = example,
                weight = 0.25 + 0.75 * position.pow(3),
            )
        }

    private data class WeightedExample(
        val example: OptimizerExample,
        val weight: Double,
    )

    private companion object {
        val ParameterStandardDeviations = floatArrayOf(
            6.43f, 9.66f, 17.58f, 27.85f, 0.57f, 0.28f, 0.6f,
            0.12f, 0.39f, 0.18f, 0.33f, 0.3f, 0.09f, 0.16f,
            0.57f, 0.25f, 1.03f, 0.31f, 0.32f, 0.14f, 0.27f,
        )
    }
}

internal class PortableBatchShuffle(seed: UInt) {
    private var state = seed

    fun permutation(size: Int): List<Int> {
        val values = MutableList(size) { it }
        for (index in values.lastIndex downTo 1) {
            state = 1_664_525u * state + 1_013_904_223u
            val selected = (state % (index + 1).toUInt()).toInt()
            val current = values[index]
            values[index] = values[selected]
            values[selected] = current
        }
        return values
    }
}

private fun roundToDecimals(value: Double, decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return round(value * factor) / factor
}
