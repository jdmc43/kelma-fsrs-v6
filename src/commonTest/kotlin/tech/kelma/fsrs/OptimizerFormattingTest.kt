package tech.kelma.fsrs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OptimizerFormattingTest {
    @Test
    fun formatsEverySyntheticOracleDataset() {
        OracleCases.forEach { expected ->
            val dataset = OptimizerDatasetFormatter(
                StudyDayPolicy.utc(),
            ).format(generateReviews(expected.spec))
            assertEquals(expected.historyHash, dataset.historySha256, expected.spec.id)
            assertEquals(expected.datasetHash, dataset.datasetSha256, expected.spec.id)
            assertEquals(expected.examples, dataset.examples.size, expected.spec.id)
            assertEquals(expected.cards, dataset.eligibility.qualifyingCards, expected.spec.id)
            assertEquals(expected.reason, dataset.eligibility.reason, expected.spec.id)
        }
    }

    @Test
    fun studyDaysHonorDayStartAndDst() {
        val utc = OptimizerDatasetFormatter(StudyDayPolicy.utc())
        val utcDays = UtcTimestamps.map(utc::studyDayEpochDay)
        assertEquals(listOf(0L, 1L, 0L, 1L), elapsed(utcDays))

        val newYork = OptimizerDatasetFormatter(
            StudyDayPolicy("America/New_York", 4),
        )
        val dstDays = NewYorkTimestamps.map(newYork::studyDayEpochDay)
        assertEquals(listOf(0L, 0L, 2L, 236L, 1L), elapsed(dstDays))
    }

    @Test
    fun duplicateRowsAreIdempotentAndCollisionsFail() {
        val review = generateReviews(OracleCases.first().spec).first()
        val formatter = OptimizerDatasetFormatter(StudyDayPolicy.utc())
        val single = formatter.format(listOf(review))
        val duplicate = formatter.format(listOf(review, review))
        assertEquals(single.historySha256, duplicate.historySha256)
        assertFailsWith<OptimizerReviewCollisionException> {
            formatter.format(listOf(review, review.copy(rating = Rating.Easy)))
        }
    }

    @Test
    fun sha256ImplementationMatchesPublishedVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc"),
        )
    }

    @Test
    fun balancedDatasetContainsBothOutcomesAndInitializationRows() {
        val dataset = balancedDataset()
        assertTrue(dataset.eligibility.eligible)
        assertEquals(1_152, dataset.eligibility.recalledOutcomes)
        assertEquals(288, dataset.eligibility.forgottenOutcomes)
        assertEquals(4, dataset.initializationRows.size)
        dataset.initializationRows.forEach {
            assertEquals(1, it.elapsedDays)
            assertEquals(30, it.count)
            assertEquals(0.8, it.recalledRate)
        }
    }

    @Test
    fun initializationMatchesOptimizerOracle() {
        val actual = OptimizerInitialization.initialize(balancedDataset())
        ExpectedInitializedParameters.forEachIndexed { index, expected ->
            assertEquals(expected, actual[index], 1e-6, "parameter $index")
        }
    }

    @Test
    fun portableShuffleMatchesOptimizerOracle() {
        val shuffle = PortableBatchShuffle(2023u)
        assertEquals(
            ExpectedBatchOrders,
            List(3) { shuffle.permutation(9) },
        )
    }

    @Test
    fun optimizationMatchesPinnedCandidateAndMetrics() {
        val result = FsrsOptimizer(StudyDayPolicy.utc()).optimize(
            generateReviews(OracleCases.first().spec),
        )
        val candidate = (result as OptimizationResult.CandidateReady).candidate
        assertEquals(ExpectedOptimizedParameters, candidate.parameters)
        assertEquals(1_152, candidate.metrics.trainingExamples)
        assertEquals(288, candidate.metrics.validationExamples)
        assertEquals(0.656645, candidate.metrics.trainingLossBefore)
        assertEquals(0.524544, candidate.metrics.trainingLossAfter)
        assertEquals(0.689809, candidate.metrics.validationLossBefore)
        assertEquals(0.553444, candidate.metrics.validationLossAfter)
    }

    @Test
    fun insufficientHistoryReturnsNoCandidate() {
        val expected = OracleCases.first { it.spec.id == "insufficient-reviews" }
        val result = FsrsOptimizer(StudyDayPolicy.utc()).optimize(generateReviews(expected.spec))
        val ineligible = result as OptimizationResult.Ineligible
        assertEquals(OptimizerIneligibilityReason.InsufficientReviews, ineligible.eligibility.reason)
    }

    @Test
    fun repeatedOptimizationIsDeterministic() {
        val reviews = generateReviews(OracleCases.first().spec)
        val optimizer = FsrsOptimizer(StudyDayPolicy.utc())
        val first = optimizer.optimize(reviews)
        val second = optimizer.optimize(reviews)
        assertEquals(first, second)
    }

    @Test
    fun cancellationAtEveryCheckpointPublishesNoCandidate() {
        OptimizationCheckpoint.entries.forEach { target ->
            var cancel = false
            val result = FsrsOptimizer(StudyDayPolicy.utc()).optimize(
                reviews = generateReviews(OracleCases.first().spec),
                cancellation = OptimizerCancellationSignal { cancel },
                progress = OptimizerProgressListener { progress ->
                    if (progress.checkpoint == target) cancel = true
                },
            )
            assertEquals(OptimizationResult.Cancelled(target), result)
        }
    }

    private fun balancedDataset(): OptimizerDataset =
        OptimizerDatasetFormatter(StudyDayPolicy.utc())
            .format(generateReviews(OracleCases.first().spec))
}

private data class SyntheticSpec(
    val id: String,
    val cards: Int,
    val reviewsPerCard: Int,
    val ratingMode: String,
    val boundaryTimes: Boolean = false,
)

private data class ExpectedCase(
    val spec: SyntheticSpec,
    val historyHash: String,
    val datasetHash: String,
    val examples: Int,
    val cards: Int,
    val reason: OptimizerIneligibilityReason?,
)

private val OracleCases = listOf(
    ExpectedCase(
        SyntheticSpec("balanced-training", 120, 14, "balanced"),
        "cbc7cd34a0d32e5f3cf21bea6a7020a417e634fe003d9bae18b383fedac74356",
        "be30919917d03ec54f3d95f8a8506ce78fa8fc8927932d0db430ff18077b3043",
        1_440,
        120,
        null,
    ),
    ExpectedCase(
        SyntheticSpec("insufficient-reviews", 80, 8, "balanced"),
        "f5902008cfa47446c8dc828d3956f758bfbfac94cecea3650dc2263ca97fc0a8",
        "d031d65ab7e97335de74420e5746f0405a60aa8daa16c21ccdbb44c74f21659e",
        480,
        80,
        OptimizerIneligibilityReason.InsufficientReviews,
    ),
    ExpectedCase(
        SyntheticSpec("insufficient-cards", 80, 16, "balanced"),
        "59e9b405c677d51097fd9db4146add7d97e5050bfd208d5a62965b08dfdcd6bc",
        "b0effbf7c430772c205006beffeefae27299171e01ee9ccdbe6a01a5baba4a2d",
        1_120,
        80,
        OptimizerIneligibilityReason.InsufficientCards,
    ),
    ExpectedCase(
        SyntheticSpec("missing-forgotten", 120, 14, "recalled"),
        "32afd436fc7683a31ae5fe0d6ab71cdb0ccba3132d519f68080d7ab4f28d8e28",
        "f15f7c944f47f9a4b2f619b74951877f8103ee5052e3e34997699f5d56be52cd",
        1_440,
        120,
        OptimizerIneligibilityReason.MissingForgottenOutcome,
    ),
    ExpectedCase(
        SyntheticSpec("missing-recalled", 120, 14, "forgotten"),
        "327b4a44807bd93f635bc1bc1ba7505883f4c738b6b68d6fb7798c3dcbd054d6",
        "e90de1d33bd9673fdf9a4fe5d372ac075a125a27d37710a9a1d1679cce6748cb",
        1_440,
        120,
        OptimizerIneligibilityReason.MissingRecalledOutcome,
    ),
    ExpectedCase(
        SyntheticSpec("day-start-boundary", 80, 6, "balanced", boundaryTimes = true),
        "c38334da2ead62d7e6555a8c47a1bae837d006b21d51d52382f1d38118b61952",
        "46cad1057ac5e9b0d051be13f34c9a84fb640009cd447ba5948b5599c3523780",
        240,
        80,
        OptimizerIneligibilityReason.InsufficientReviews,
    ),
)

private fun generateReviews(spec: SyntheticSpec): List<OptimizerReview> {
    val intervals = listOf(0, 1, 2, 4, 7, 14, 3, 21, 5, 30, 10, 45, 8, 60, 12, 90)
    return buildList {
        repeat(spec.cards) { cardIndex ->
            var reviewedAt = if (spec.boundaryTimes) {
                1_577_850_600_000L + cardIndex * 1_000L
            } else {
                1_577_880_000_000L + cardIndex * 1_000L
            }
            var priorRating = Rating.Good
            repeat(spec.reviewsPerCard) { reviewIndex ->
                if (reviewIndex > 0) {
                    reviewedAt += if (spec.boundaryTimes) {
                        if (reviewIndex % 2 == 1) 20 * 60_000L else (23 * 60 + 40) * 60_000L
                    } else {
                        val days = intervals[(reviewIndex - 1) % intervals.size]
                        if (days == 0) 10 * 60_000L else days * 86_400_000L
                    }
                }
                val rating = syntheticRating(spec.ratingMode, cardIndex, reviewIndex)
                val state = when {
                    reviewIndex == 0 -> OptimizerReviewState.New
                    priorRating == Rating.Again -> OptimizerReviewState.Relearning
                    reviewIndex == 1 -> OptimizerReviewState.Learning
                    else -> OptimizerReviewState.Review
                }
                add(
                    OptimizerReview(
                        reviewId = reviewedAt,
                        cardIdentity = "card-${(cardIndex + 1).toString().padStart(4, '0')}",
                        reviewedAtMillis = reviewedAt,
                        rating = rating,
                        state = state,
                        durationMillis = 2_500L + ((cardIndex * 31 + reviewIndex * 101) % 8_000),
                    ),
                )
                priorRating = rating
            }
        }
    }
}

private fun syntheticRating(mode: String, cardIndex: Int, reviewIndex: Int): Rating {
    if (mode == "recalled") return Rating.Good
    if (mode == "forgotten") return Rating.Again
    if (reviewIndex == 0) return Rating.fromValue(cardIndex % 4 + 1)
    val selector = (cardIndex * 17 + reviewIndex * 7) % 20
    return Rating.fromValue(
        when {
            selector < 4 -> 1
            selector < 8 -> 2
            selector < 17 -> 3
            else -> 4
        },
    )
}

private fun elapsed(days: List<Long>): List<Long> =
    listOf(0L) + days.zipWithNext { left, right -> right - left }

private val ExpectedInitializedParameters = listOf(
    0.298215645441, 0.305029823671, 0.305029823671, 0.305030182866,
    6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
    1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425,
    0.0912, 0.0658, 0.1542,
)

private val ExpectedOptimizedParameters = listOf(
    0.1368, 0.1207, 0.0826, 0.0825, 6.6868, 0.5799, 3.3569,
    0.001, 1.457, 0.3303, 0.3457, 1.6323, 0.0251, 0.447,
    1.7984, 0.4147, 1.8589, 0.4945, 0.1149, 0.1016, 0.1,
)

private val ExpectedBatchOrders = listOf(
    listOf(0, 6, 3, 5, 2, 7, 4, 1, 8),
    listOf(0, 4, 2, 5, 3, 6, 7, 8, 1),
    listOf(0, 4, 8, 5, 3, 7, 6, 1, 2),
)

private val UtcTimestamps = listOf(
    1_704_081_000_000L,
    1_704_082_200_000L,
    1_704_167_400_000L,
    1_704_168_600_000L,
)

private val NewYorkTimestamps = listOf(
    1_709_976_600_000L,
    1_710_059_400_000L,
    1_710_145_800_000L,
    1_730_536_200_000L,
    1_730_626_200_000L,
)
