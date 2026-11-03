#!/usr/bin/env python3
"""Verify the pinned optimizer fixture without importing Python oracle packages."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "testdata" / "fsrs-optimizer-6.5.0-vectors.json"
EXPECTED_SHA256 = "6e7bbe6ef2b0e13cb2fd869017fe1dc4e5903163cfeb6f5577467ca9af87453a"
EXPECTED_REASONS = {
    "balanced-training": None,
    "insufficient-reviews": "insufficient_reviews",
    "insufficient-cards": "insufficient_cards",
    "missing-forgotten": "missing_forgotten_outcome",
    "missing-recalled": "missing_recalled_outcome",
    "day-start-boundary": "insufficient_reviews",
}
REVIEW_KEYS = {
    "reviewId",
    "cardIdentity",
    "reviewedAtMillis",
    "rating",
    "state",
    "durationMillis",
}


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def history_hash(reviews: list[dict[str, Any]]) -> str:
    digest = hashlib.sha256()
    for review in sorted(reviews, key=lambda item: (item["reviewId"], item["cardIdentity"])):
        identity = review["cardIdentity"].encode("utf-8")
        digest.update(
            f"{review['reviewId']}\n{len(identity)}:".encode("utf-8")
            + identity
            + (
                f"\n{review['reviewedAtMillis']}\n{review['rating']}\n"
                f"{review['state']}\n{review['durationMillis']}\n"
            ).encode("utf-8")
        )
    return digest.hexdigest()


def main() -> None:
    payload = FIXTURE.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    if digest != EXPECTED_SHA256:
        raise SystemExit(f"optimizer fixture SHA-256 {digest}, expected {EXPECTED_SHA256}")
    fixture = json.loads(payload)
    oracle = fixture["oracle"]
    if (
        fixture["schemaVersion"] != 2
        or oracle["packageVersion"] != "6.5.0"
        or oracle["releaseCommit"] != "cf052b7843452369485f9d39f9ada562ba152ea8"
        or oracle["containerPlatform"] != "linux/amd64"
        or oracle["wheelSha256"]
        != "245ea144584c3db2bbdfdccb3beebf29978a400aa01f8cd33bf947b6f7cff20c"
        or oracle["requirementsSha256"]
        != hashlib.sha256(
            (ROOT / "tools" / "optimizer_oracle" / "requirements.txt").read_bytes()
        ).hexdigest()
    ):
        raise SystemExit("optimizer fixture metadata mismatch")

    training = fixture["trainingPolicy"]
    if (
        training["seed"] != 2023
        or training["shuffle"] != "lcg32-fisher-yates"
        or training["shuffleMultiplier"] != 1664525
        or training["shuffleIncrement"] != 1013904223
        or training["epochs"] != 3
        or training["batchSize"] != 128
    ):
        raise SystemExit("portable optimizer training policy mismatch")

    cases = {case["id"]: case for case in fixture["cases"]}
    if set(cases) != set(EXPECTED_REASONS):
        raise SystemExit("optimizer fixture cases changed")
    for identifier, reason in EXPECTED_REASONS.items():
        case = cases[identifier]
        if case["eligibility"]["reason"] != reason:
            raise SystemExit(f"{identifier}: eligibility mismatch")
        if case["historySha256"] != history_hash(case["rawReviews"]):
            raise SystemExit(f"{identifier}: history hash mismatch")
        if case["datasetSha256"] != canonical_hash(case["examples"]):
            raise SystemExit(f"{identifier}: dataset hash mismatch")
        if any(set(review) != REVIEW_KEYS for review in case["rawReviews"]):
            raise SystemExit(f"{identifier}: raw fixture contains unexpected fields")
        if any(
            set(row) != {"firstRating", "elapsedDays", "recalledRate", "count"}
            for row in case["initializationRows"]
        ):
            raise SystemExit(f"{identifier}: initialization schema mismatch")

    study_days = {vector["id"]: vector for vector in fixture["studyDayVectors"]}
    if study_days["utc-day-start"]["elapsedDays"] != [0, 1, 0, 1]:
        raise SystemExit("UTC day-start vectors changed")
    if study_days["new-york-dst"]["elapsedDays"] != [0, 0, 2, 236, 1]:
        raise SystemExit("DST day-start vectors changed")

    balanced = cases["balanced-training"]["eligibility"]
    if (
        not balanced["eligible"]
        or balanced["qualifyingReviews"] < 1_000
        or balanced["qualifyingCards"] < 100
        or balanced["recalledOutcomes"] == 0
        or balanced["forgottenOutcomes"] == 0
    ):
        raise SystemExit("balanced optimizer case is not eligible")

    optimization = fixture["optimization"]
    parameters = optimization["optimized"]["parameters"]
    lower = fixture["parameterBounds"]["lower"]
    upper = fixture["parameterBounds"]["upper"]
    if len(parameters) != 21 or not all(
        low <= value <= high for value, low, high in zip(parameters, lower, upper)
    ):
        raise SystemExit("optimized parameter bounds mismatch")
    for prefix in ("training", "validation"):
        if optimization["optimized"][f"{prefix}LossAfter"] >= optimization["optimized"][f"{prefix}LossBefore"]:
            raise SystemExit(f"{prefix} loss did not improve")
    if not optimization["deterministicRepeatMatches"]:
        raise SystemExit("optimizer repeat was not deterministic")
    if optimization["optimized"]["batchOrders"] != [
        [0, 6, 3, 5, 2, 7, 4, 1, 8],
        [0, 4, 2, 5, 3, 6, 7, 8, 1],
        [0, 4, 8, 5, 3, 7, 6, 1, 2],
    ]:
        raise SystemExit("portable optimizer batch orders changed")

    cancellation = fixture["cancellationContract"]
    if (
        cancellation["expectedStatus"] != "cancelled"
        or cancellation["candidateAfterCancellation"] is not None
        or cancellation["checkpoints"]
        != [
            "before_formatting",
            "after_formatting",
            "between_epochs",
            "before_candidate_publication",
        ]
    ):
        raise SystemExit("cancellation contract mismatch")
    print(f"verified optimizer fixture {digest}")


if __name__ == "__main__":
    main()
