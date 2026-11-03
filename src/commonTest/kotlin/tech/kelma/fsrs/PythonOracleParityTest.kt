package tech.kelma.fsrs

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class PythonOracleParityTest {
    private val fixture: OracleFixture by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(OracleFixture.serializer(), OracleFixtureJson)
    }

    @Test
    fun fixtureMetadataIsPinned() {
        assertEquals(1, fixture.schemaVersion)
        assertEquals(705, fixture.caseCount)
        assertEquals(705, fixture.cases.size)
        assertEquals(11, fixture.configurations.size)
        assertEquals("fsrs", fixture.oracle.`package`)
        assertEquals("6.3.1", fixture.oracle.packageVersion)
        assertEquals("FSRS-6", fixture.oracle.algorithmVersion)
        assertEquals("3abe686e9c058d3f3c00bbeb92e68b71211b2b31", fixture.oracle.releaseCommit)
        assertEquals("linux/amd64", fixture.oracle.containerPlatform)
        assertEquals(64, OracleFixtureSha256.length)
    }

    @Test
    fun schedulerMatchesEveryPinnedPythonCase() {
        val configurations = fixture.configurations.associate { input ->
            input.id to input.toSchedulerConfig()
        }
        fixture.cases.forEach { testCase ->
            val scheduler = Scheduler(
                configurations[testCase.configurationId]
                    ?: error("Unknown configuration ${testCase.configurationId}"),
            )
            val initial = testCase.initialCard.toCard()
            val events = testCase.reviews.map { it.toReviewEvent(initial.id) }
            val results = scheduler.replay(initial, events)
            assertEquals(testCase.expected.size, results.size, testCase.name)

            var current = initial
            val ordered = events.distinctBy { it.id }.sortedWith(
                compareBy<ReviewEvent> { it.reviewedAtMillis }.thenBy { it.id },
            )
            testCase.expected.forEachIndexed { index, expected ->
                val result = results[index]
                assertEquals(testCase.canonicalReviewOrder[index], result.event.id, testCase.name)
                assertEquals(expected.reviewId, result.event.id, testCase.name)
                assertClose(
                    expected.retrievabilityBefore,
                    scheduler.retrievability(current, ordered[index].reviewedAtMillis),
                    "${testCase.name} retrievability before step $index",
                )
                assertCard(expected.card, result.card, testCase.name, index)
                assertClose(
                    expected.retrievabilityAtDue,
                    scheduler.retrievability(result.card, result.card.dueAtMillis),
                    "${testCase.name} retrievability at due step $index",
                )
                current = result.card
            }
        }
    }
}

private fun OracleConfiguration.toSchedulerConfig(): SchedulerConfig = SchedulerConfig(
    parameters = parameters,
    desiredRetention = desiredRetention,
    learningSteps = learningStepsSeconds.map { it.seconds },
    relearningSteps = relearningStepsSeconds.map { it.seconds },
    maximumIntervalDays = maximumInterval,
    enableShortTerm = true,
    enableFuzzing = enableFuzzing,
)

private fun OracleCard.toCard(): Card = Card(
    id = cardId,
    state = State.valueOf(state),
    step = step,
    stability = stability,
    difficulty = difficulty,
    dueAtMillis = dueAtMillis,
    lastReviewAtMillis = lastReviewAtMillis,
)

private fun OracleReview.toReviewEvent(cardId: Long): ReviewEvent = ReviewEvent(
    id = reviewId,
    cardId = cardId,
    rating = Rating.valueOf(rating),
    reviewedAtMillis = reviewedAtMillis,
)

private fun assertCard(expected: OracleCard, actual: Card, caseName: String, step: Int) {
    val prefix = "$caseName card step $step"
    assertEquals(expected.cardId, actual.id, prefix)
    assertEquals(expected.state, actual.state.name, prefix)
    assertEquals(expected.step, actual.step, prefix)
    assertOptionalClose(expected.stability, actual.stability, "$prefix stability")
    assertOptionalClose(expected.difficulty, actual.difficulty, "$prefix difficulty")
    assertEquals(expected.dueAtMillis, actual.dueAtMillis, "$prefix due")
    assertEquals(expected.lastReviewAtMillis, actual.lastReviewAtMillis, "$prefix last review")
}

private fun assertOptionalClose(expected: Double?, actual: Double?, message: String) {
    if (expected == null) {
        assertNull(actual, message)
    } else {
        assertClose(expected, assertNotNull(actual, message), message)
    }
}

private fun assertClose(expected: Double, actual: Double, message: String) {
    val tolerance = max(1e-12, abs(expected) * 1e-12)
    assertTrue(
        actual.isFinite() && abs(actual - expected) <= tolerance,
        "$message: got $actual, expected $expected (tolerance $tolerance)",
    )
}

@Serializable
private data class OracleFixture(
    val schemaVersion: Int,
    val oracle: OracleMetadata,
    val caseCount: Int,
    val configurations: List<OracleConfiguration>,
    val cases: List<OracleCase>,
)

@Serializable
private data class OracleMetadata(
    val `package`: String,
    val packageVersion: String,
    val algorithmVersion: String,
    val releaseCommit: String,
    val containerPlatform: String,
)

@Serializable
private data class OracleConfiguration(
    val id: String,
    val parameters: List<Double>,
    val desiredRetention: Double,
    val learningStepsSeconds: List<Long>,
    val relearningStepsSeconds: List<Long>,
    val maximumInterval: Int,
    val enableFuzzing: Boolean,
)

@Serializable
private data class OracleCase(
    val name: String,
    val configurationId: String,
    val initialCard: OracleCard,
    val reviews: List<OracleReview>,
    val canonicalReviewOrder: List<Long>,
    val expected: List<OracleExpectedStep>,
)

@Serializable
private data class OracleCard(
    val cardId: Long,
    val state: String,
    val step: Int?,
    val stability: Double?,
    val difficulty: Double?,
    val dueAtMillis: Long,
    val lastReviewAtMillis: Long?,
)

@Serializable
private data class OracleReview(
    val reviewId: Long,
    val reviewedAtMillis: Long,
    val rating: String,
)

@Serializable
private data class OracleExpectedStep(
    val reviewId: Long,
    val retrievabilityBefore: Double,
    val card: OracleCard,
    val retrievabilityAtDue: Double,
)
