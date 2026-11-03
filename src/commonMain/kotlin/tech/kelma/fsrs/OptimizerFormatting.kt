package tech.kelma.fsrs

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class OptimizerDatasetFormatter(
    studyDayPolicy: StudyDayPolicy,
    trainingPolicy: OptimizerTrainingPolicy = OptimizerTrainingPolicy(),
) {
    private val policy = trainingPolicy.validated()
    private val dayStartHour = studyDayPolicy.dayStartHour
    private val timeZone = TimeZone.of(studyDayPolicy.timeZoneId)

    init {
        require(dayStartHour in 0..23) { "dayStartHour must be in [0,23]" }
    }

    fun format(reviews: Iterable<OptimizerReview>): OptimizerDataset {
        val canonicalReviews = deduplicateAndValidate(reviews)
        val rows = canonicalReviews
            .groupBy { it.cardIdentity }
            .entries
            .sortedBy { it.key }
            .flatMap { entry -> formatCard(entry.value) }
        val filtered = removeInitializationOutliers(rows)
            .groupBy { it.cardIdentity }
            .entries
            .sortedBy { it.key }
            .flatMap { entry -> continuousPrefix(entry.value) }
        val examples = filtered
            .asSequence()
            .filter { it.sequence > 1 && it.elapsedDays > 0 }
            .map { it.toExample() }
            .sortedWith(ExampleOrder)
            .toList()
        val initializationRows = initializationRows(filtered)
        val eligibility = eligibility(examples)
        val throughReviewId = canonicalReviews.maxWithOrNull(ReviewChronologicalOrder)?.reviewId
        return OptimizerDataset(
            examples = examples,
            initializationRows = initializationRows,
            eligibility = eligibility,
            historySha256 = historyHash(canonicalReviews),
            datasetSha256 = datasetHash(examples),
            throughReviewId = throughReviewId,
        )
    }

    fun studyDayEpochDay(reviewedAtMillis: Long): Long {
        val shifted = Instant.fromEpochMilliseconds(reviewedAtMillis) - dayStartHour.hours
        return shifted.toLocalDateTime(timeZone).date.toEpochDays()
    }

    private fun deduplicateAndValidate(
        reviews: Iterable<OptimizerReview>,
    ): List<OptimizerReview> {
        val byId = mutableMapOf<Long, OptimizerReview>()
        reviews.forEach { review ->
            require(review.cardIdentity.isNotEmpty()) { "cardIdentity must not be empty" }
            require(review.durationMillis == null || review.durationMillis >= 0L) {
                "durationMillis must be non-negative"
            }
            val existing = byId[review.reviewId]
            when {
                existing == null -> byId[review.reviewId] = review
                existing != review -> throw OptimizerReviewCollisionException(review.reviewId)
            }
        }
        return byId.values.sortedWith(ReviewCardOrder)
    }

    private fun formatCard(cardReviews: List<OptimizerReview>): List<FormatRow> {
        val ordered = cardReviews.sortedWith(ReviewChronologicalOrder)
        val elapsedHistory = mutableListOf<Int>()
        val ratingHistory = mutableListOf<Rating>()
        val rows = mutableListOf<FormatRow>()
        var previousStudyDay: Long? = null
        for (review in ordered) {
            val studyDay = studyDayEpochDay(review.reviewedAtMillis)
            val elapsedDays = previousStudyDay?.let {
                val difference = studyDay - it
                require(difference in Int.MIN_VALUE..Int.MAX_VALUE) {
                    "elapsed study days exceed the supported integer range"
                }
                difference.toInt()
            } ?: -1
            val lastCrossDayRating = elapsedHistory.indices
                .lastOrNull { elapsedHistory[it] > 0 }
                ?.let { ratingHistory[it] }
                ?: ratingHistory.firstOrNull()
                ?: review.rating
            rows += FormatRow(
                review = review,
                elapsedDays = elapsedDays,
                historyElapsedDays = elapsedHistory.map { it.coerceAtLeast(0) },
                historyRatings = ratingHistory.toList(),
                sequence = 0,
                firstRating = ratingHistory.firstOrNull(),
                lastCrossDayRating = lastCrossDayRating,
                recalled = review.rating != Rating.Again,
            )
            elapsedHistory += elapsedDays
            ratingHistory += review.rating
            previousStudyDay = studyDay
        }
        return rows
            .filter { it.elapsedDays != 0 }
            .mapIndexed { index, row -> row.copy(sequence = index + 1) }
    }

    private fun removeInitializationOutliers(rows: List<FormatRow>): List<FormatRow> {
        val removed = mutableSetOf<Long>()
        rows.filter { it.sequence == 2 && it.firstRating != null }
            .groupBy { it.firstRating!! }
            .forEach { (firstRating, ratingRows) ->
                val aggregates = ratingRows.groupBy { it.elapsedDays }
                    .map { (elapsedDays, grouped) ->
                        ElapsedAggregate(elapsedDays, grouped.size)
                    }
                    .sortedWith(
                        compareBy<ElapsedAggregate> { it.count }
                            .thenByDescending { it.elapsedDays },
                    )
                val total = ratingRows.size
                var removedCount = 0
                for (aggregate in aggregates) {
                    val threshold = maxOf(total * 0.05, 20.0)
                    val remove = if (removedCount + aggregate.count >= threshold) {
                        aggregate.count < 6 ||
                            aggregate.elapsedDays > if (firstRating == Rating.Easy) 365 else 100
                    } else {
                        true
                    }
                    if (remove) {
                        ratingRows
                            .filter { it.elapsedDays == aggregate.elapsedDays }
                            .forEach { removed += it.review.reviewId }
                        removedCount += aggregate.count
                    }
                }
            }
        return rows.filterNot { it.review.reviewId in removed }
    }

    private fun continuousPrefix(rows: List<FormatRow>): List<FormatRow> {
        val result = mutableListOf<FormatRow>()
        var expected = 1
        for (row in rows.sortedBy { it.sequence }) {
            if (row.sequence != expected) break
            result += row
            expected += 1
        }
        return result
    }

    private fun initializationRows(rows: List<FormatRow>): List<OptimizerInitializationRow> =
        rows.asSequence()
            .filter { it.sequence == 2 && it.firstRating != null }
            .groupBy { it.firstRating!! to it.elapsedDays }
            .map { (key, grouped) ->
                OptimizerInitializationRow(
                    firstRating = key.first,
                    elapsedDays = key.second,
                    recalledRate = grouped.count { it.recalled }.toDouble() / grouped.size,
                    count = grouped.size,
                )
            }
            .sortedWith(compareBy({ it.firstRating.value }, { it.elapsedDays }))

    private fun eligibility(examples: List<OptimizerExample>): OptimizerEligibility {
        val cards = examples.asSequence().map { it.cardIdentity }.toSet().size
        val recalled = examples.count { it.recalled }
        val forgotten = examples.size - recalled
        val reason = when {
            examples.size < policy.minimumReviews -> OptimizerIneligibilityReason.InsufficientReviews
            cards < policy.minimumCards -> OptimizerIneligibilityReason.InsufficientCards
            forgotten == 0 -> OptimizerIneligibilityReason.MissingForgottenOutcome
            recalled == 0 -> OptimizerIneligibilityReason.MissingRecalledOutcome
            else -> null
        }
        return OptimizerEligibility(
            eligible = reason == null,
            reason = reason,
            qualifyingReviews = examples.size,
            qualifyingCards = cards,
            recalledOutcomes = recalled,
            forgottenOutcomes = forgotten,
            minimumReviews = policy.minimumReviews,
            minimumCards = policy.minimumCards,
        )
    }

    private data class FormatRow(
        val review: OptimizerReview,
        val elapsedDays: Int,
        val historyElapsedDays: List<Int>,
        val historyRatings: List<Rating>,
        val sequence: Int,
        val firstRating: Rating?,
        val lastCrossDayRating: Rating,
        val recalled: Boolean,
    ) {
        val cardIdentity: String get() = review.cardIdentity

        fun toExample(): OptimizerExample = OptimizerExample(
            reviewId = review.reviewId,
            cardIdentity = review.cardIdentity,
            reviewedAtMillis = review.reviewedAtMillis,
            rating = review.rating,
            elapsedDays = elapsedDays,
            historyElapsedDays = historyElapsedDays,
            historyRatings = historyRatings,
            sequence = sequence,
            lastCrossDayRating = lastCrossDayRating,
            recalled = recalled,
        )
    }

    private data class ElapsedAggregate(val elapsedDays: Int, val count: Int)

    private companion object {
        val ReviewCardOrder = compareBy<OptimizerReview>(
            { it.cardIdentity },
            { it.reviewedAtMillis },
            { it.reviewId },
        )
        val ReviewChronologicalOrder = compareBy<OptimizerReview>(
            { it.reviewedAtMillis },
            { it.reviewId },
            { it.cardIdentity },
        )
        val ExampleOrder = compareBy<OptimizerExample>(
            { it.reviewedAtMillis },
            { it.cardIdentity },
            { it.reviewId },
        )
    }
}

private fun historyHash(reviews: List<OptimizerReview>): String {
    val digest = Sha256()
    reviews.sortedWith(compareBy({ it.reviewId }, { it.cardIdentity })).forEach { review ->
        val identity = review.cardIdentity.encodeToByteArray()
        digest.update("${review.reviewId}\n${identity.size}:")
        digest.update(identity)
        digest.update(
            "\n${review.reviewedAtMillis}\n${review.rating.value}\n" +
                "${review.state?.value ?: -1}\n${review.durationMillis ?: 0L}\n",
        )
    }
    return digest.hexDigest()
}

private fun datasetHash(examples: List<OptimizerExample>): String {
    val digest = Sha256().update("[")
    examples.forEachIndexed { index, example ->
        if (index > 0) digest.update(",")
        digest.update(canonicalExample(example))
    }
    return digest.update("]").hexDigest()
}

private fun canonicalExample(example: OptimizerExample): String = buildString {
    append("{\"cardIdentity\":\"")
    append(jsonEscape(example.cardIdentity))
    append("\",\"elapsedDays\":${example.elapsedDays}")
    append(",\"historyElapsedDays\":[")
    append(example.historyElapsedDays.joinToString(","))
    append("],\"historyRatings\":[")
    append(example.historyRatings.joinToString(",") { it.value.toString() })
    append("],\"lastCrossDayRating\":${example.lastCrossDayRating.value}")
    append(",\"rating\":${example.rating.value}")
    append(",\"recalled\":${example.recalled}")
    append(",\"reviewId\":${example.reviewId}")
    append(",\"reviewedAtMillis\":${example.reviewedAtMillis}")
    append(",\"sequence\":${example.sequence}}")
}

private fun jsonEscape(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000c' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code in 0x20..0x7f) {
                append(character)
            } else {
                append("\\u")
                append(character.code.toString(16).padStart(4, '0'))
            }
        }
    }
}
