package tech.kelma.fsrs

fun Scheduler.replay(initial: Card, events: Iterable<ReviewEvent>): List<ReviewResult> {
    val byId = linkedMapOf<Long, ReviewEvent>()
    events.forEach { event ->
        val prior = byId[event.id]
        when {
            prior == null -> byId[event.id] = event
            prior != event -> throw ReviewCollisionException(event.id)
        }
    }
    val ordered = byId.values.sortedWith(
        compareBy<ReviewEvent> { it.reviewedAtMillis }.thenBy { it.id },
    )
    var current = initial
    return ordered.map { event ->
        require(event.cardId == current.id) {
            "Review ${event.id} belongs to card ${event.cardId}, expected ${current.id}"
        }
        current = review(current, event.rating, event.reviewedAtMillis)
        ReviewResult(card = current, event = event)
    }
}
