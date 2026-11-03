package tech.kelma.fsrs

enum class OptimizerReviewState(val value: Int) {
    New(0),
    Learning(1),
    Review(2),
    Relearning(3),
}

/** Content-free immutable review input for local parameter optimization. */
data class OptimizerReview(
    val reviewId: Long,
    val cardIdentity: String,
    val reviewedAtMillis: Long,
    val rating: Rating,
    val state: OptimizerReviewState? = null,
    val durationMillis: Long? = null,
)

data class StudyDayPolicy(
    val timeZoneId: String,
    val dayStartHour: Int,
) {
    companion object {
        fun utc(dayStartHour: Int = 4): StudyDayPolicy =
            StudyDayPolicy(timeZoneId = "UTC", dayStartHour = dayStartHour)
    }
}

enum class OptimizerIneligibilityReason(val code: String) {
    InsufficientReviews("insufficient_reviews"),
    InsufficientCards("insufficient_cards"),
    MissingRecalledOutcome("missing_recalled_outcome"),
    MissingForgottenOutcome("missing_forgotten_outcome"),
}

data class OptimizerEligibility(
    val eligible: Boolean,
    val reason: OptimizerIneligibilityReason?,
    val qualifyingReviews: Int,
    val qualifyingCards: Int,
    val recalledOutcomes: Int,
    val forgottenOutcomes: Int,
    val minimumReviews: Int,
    val minimumCards: Int,
)

data class OptimizerExample(
    val reviewId: Long,
    val cardIdentity: String,
    val reviewedAtMillis: Long,
    val rating: Rating,
    val elapsedDays: Int,
    val historyElapsedDays: List<Int>,
    val historyRatings: List<Rating>,
    val sequence: Int,
    val lastCrossDayRating: Rating,
    val recalled: Boolean,
)

data class OptimizerInitializationRow(
    val firstRating: Rating,
    val elapsedDays: Int,
    val recalledRate: Double,
    val count: Int,
)

data class OptimizerDataset(
    val examples: List<OptimizerExample>,
    val initializationRows: List<OptimizerInitializationRow>,
    val eligibility: OptimizerEligibility,
    val historySha256: String,
    val datasetSha256: String,
    val throughReviewId: Long?,
)

data class OptimizerTrainingPolicy(
    val minimumReviews: Int = 1_000,
    val minimumCards: Int = 100,
    val epochs: Int = 3,
    val learningRate: Double = 0.04,
    val regularizationGamma: Double = 1.0,
    val batchSize: Int = 128,
    val maximumSequenceLength: Int = 64,
    val validationPercent: Int = 20,
    val seed: UInt = 2023u,
) {
    internal fun validated(): OptimizerTrainingPolicy {
        require(minimumReviews > 0) { "minimumReviews must be positive" }
        require(minimumCards > 0) { "minimumCards must be positive" }
        require(epochs > 0) { "epochs must be positive" }
        require(learningRate.isFinite() && learningRate > 0.0) {
            "learningRate must be positive and finite"
        }
        require(regularizationGamma.isFinite() && regularizationGamma >= 0.0) {
            "regularizationGamma must be non-negative and finite"
        }
        require(batchSize > 0) { "batchSize must be positive" }
        require(maximumSequenceLength > 0) { "maximumSequenceLength must be positive" }
        require(validationPercent in 1..99) { "validationPercent must be in [1,99]" }
        return this
    }
}

enum class OptimizationCheckpoint {
    BeforeFormatting,
    AfterFormatting,
    BetweenEpochs,
    BeforeCandidatePublication,
}

data class OptimizerProgress(
    val checkpoint: OptimizationCheckpoint,
    val completedEpochs: Int,
    val totalEpochs: Int,
    val qualifyingReviews: Int,
)

fun interface OptimizerCancellationSignal {
    fun isCancellationRequested(): Boolean

    companion object {
        val Never = OptimizerCancellationSignal { false }
    }
}

fun interface OptimizerProgressListener {
    fun onProgress(progress: OptimizerProgress)

    companion object {
        val None = OptimizerProgressListener { }
    }
}

data class OptimizerMetrics(
    val trainingExamples: Int,
    val validationExamples: Int,
    val trainingLossBefore: Double,
    val trainingLossAfter: Double,
    val validationLossBefore: Double,
    val validationLossAfter: Double,
)

data class OptimizerCandidate(
    val parameters: List<Double>,
    val initializedParameters: List<Double>,
    val metrics: OptimizerMetrics,
    val historySha256: String,
    val datasetSha256: String,
    val throughReviewId: Long?,
    val qualifyingReviews: Int,
    val qualifyingCards: Int,
    val optimizerVersion: String = OptimizerVersion,
)

sealed interface OptimizationResult {
    data class CandidateReady(val candidate: OptimizerCandidate) : OptimizationResult

    data class Ineligible(
        val eligibility: OptimizerEligibility,
        val historySha256: String,
        val datasetSha256: String,
    ) : OptimizationResult

    data class Cancelled(val checkpoint: OptimizationCheckpoint) : OptimizationResult
}

class OptimizerReviewCollisionException(val reviewId: Long) :
    IllegalArgumentException("conflicting optimizer reviews share ID $reviewId")

val OptimizerParameterLowerBounds: List<Double> = listOf(
    0.001, 0.001, 0.001, 0.001,
    1.0, 0.001, 0.001, 0.001,
    0.0, 0.0, 0.001, 0.001,
    0.001, 0.001, 0.0, 0.0,
    1.0, 0.0, 0.0, 0.01,
    0.1,
)

val OptimizerParameterUpperBounds: List<Double> = ParameterUpperBounds.toList()

const val OptimizerVersion: String = "fsrs-optimizer-6.5.0-compatible-2"
