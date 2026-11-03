package tech.kelma.fsrs

class FsrsOptimizer(
    private val studyDayPolicy: StudyDayPolicy,
    trainingPolicy: OptimizerTrainingPolicy = OptimizerTrainingPolicy(),
) {
    private val policy = trainingPolicy.validated()

    fun optimize(
        reviews: Iterable<OptimizerReview>,
        cancellation: OptimizerCancellationSignal = OptimizerCancellationSignal.Never,
        progress: OptimizerProgressListener = OptimizerProgressListener.None,
    ): OptimizationResult {
        if (checkpoint(
                checkpoint = OptimizationCheckpoint.BeforeFormatting,
                completedEpochs = 0,
                qualifyingReviews = 0,
                cancellation = cancellation,
                progress = progress,
            )
        ) {
            return OptimizationResult.Cancelled(OptimizationCheckpoint.BeforeFormatting)
        }
        val dataset = OptimizerDatasetFormatter(studyDayPolicy, policy).format(reviews)
        if (checkpoint(
                checkpoint = OptimizationCheckpoint.AfterFormatting,
                completedEpochs = 0,
                qualifyingReviews = dataset.examples.size,
                cancellation = cancellation,
                progress = progress,
            )
        ) {
            return OptimizationResult.Cancelled(OptimizationCheckpoint.AfterFormatting)
        }
        if (!dataset.eligibility.eligible) {
            return OptimizationResult.Ineligible(
                eligibility = dataset.eligibility,
                historySha256 = dataset.historySha256,
                datasetSha256 = dataset.datasetSha256,
            )
        }

        val initialized = OptimizerInitialization.initialize(dataset)
        val training = OptimizerTrainer(policy).train(dataset, initialized) { completedEpochs ->
            !checkpoint(
                checkpoint = OptimizationCheckpoint.BetweenEpochs,
                completedEpochs = completedEpochs,
                qualifyingReviews = dataset.examples.size,
                cancellation = cancellation,
                progress = progress,
            )
        } ?: return OptimizationResult.Cancelled(OptimizationCheckpoint.BetweenEpochs)

        if (checkpoint(
                checkpoint = OptimizationCheckpoint.BeforeCandidatePublication,
                completedEpochs = policy.epochs,
                qualifyingReviews = dataset.examples.size,
                cancellation = cancellation,
                progress = progress,
            )
        ) {
            return OptimizationResult.Cancelled(OptimizationCheckpoint.BeforeCandidatePublication)
        }
        validateCandidate(training.parameters)
        return OptimizationResult.CandidateReady(
            OptimizerCandidate(
                parameters = training.parameters,
                initializedParameters = initialized,
                metrics = training.metrics,
                historySha256 = dataset.historySha256,
                datasetSha256 = dataset.datasetSha256,
                throughReviewId = dataset.throughReviewId,
                qualifyingReviews = dataset.eligibility.qualifyingReviews,
                qualifyingCards = dataset.eligibility.qualifyingCards,
            ),
        )
    }

    private fun checkpoint(
        checkpoint: OptimizationCheckpoint,
        completedEpochs: Int,
        qualifyingReviews: Int,
        cancellation: OptimizerCancellationSignal,
        progress: OptimizerProgressListener,
    ): Boolean {
        progress.onProgress(
            OptimizerProgress(
                checkpoint = checkpoint,
                completedEpochs = completedEpochs,
                totalEpochs = policy.epochs,
                qualifyingReviews = qualifyingReviews,
            ),
        )
        return cancellation.isCancellationRequested()
    }

    private fun validateCandidate(parameters: List<Double>) {
        require(parameters.size == OptimizerParameterCount) {
            "optimizer must produce 21 parameters"
        }
        parameters.forEachIndexed { index, value ->
            require(value.isFinite() && value in
                OptimizerParameterLowerBounds[index]..OptimizerParameterUpperBounds[index]
            ) {
                "optimized parameter[$index]=$value is outside its bounds"
            }
        }
    }
}
