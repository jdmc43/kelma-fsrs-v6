#!/usr/bin/env python3
"""Compare a temporary private review fixture with the pinned optimizer oracle."""

from __future__ import annotations

import csv
import json
import sys
from pathlib import Path
from typing import Any

import pandas as pd
from fsrs_optimizer import Optimizer

from generate import (
    canonical_hash,
    eligibility,
    format_examples,
    history_hash,
    optimization_result,
    temporary_working_directory,
    write_revlog,
)


def read_reviews(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8", newline="") as source:
        rows = csv.DictReader(source, delimiter="\t")
        expected = {
            "reviewId",
            "cardIdentity",
            "reviewedAtMillis",
            "rating",
            "state",
            "durationMillis",
        }
        if set(rows.fieldnames or ()) != expected:
            raise RuntimeError("private optimizer fixture schema mismatch")
        reviews = [
            {
                "reviewId": int(row["reviewId"]),
                "cardIdentity": row["cardIdentity"],
                "reviewedAtMillis": int(row["reviewedAtMillis"]),
                "rating": int(row["rating"]),
                "state": int(row["state"]),
                "durationMillis": int(row["durationMillis"]),
            }
            for row in rows
        ]
    if not reviews:
        raise RuntimeError("private optimizer fixture contains no reviews")
    return reviews


def format_real(
    reviews: list[dict[str, Any]],
    timezone_name: str,
    day_start_hour: int,
) -> tuple[list[dict[str, Any]], pd.DataFrame, Optimizer]:
    with temporary_working_directory():
        write_revlog(reviews)
        optimizer = Optimizer(float_delta_t=False, enable_short_term=True)
        optimizer.create_time_series(
            timezone_name,
            "2006-01-01",
            day_start_hour,
            analysis=False,
        )
        dataframe = pd.read_csv(
            "revlog_history.tsv",
            sep="\t",
            dtype={"r_history": str, "t_history": str},
        )
    return format_examples(dataframe), dataframe, optimizer


def close_list(
    name: str,
    expected: list[float],
    actual: list[float],
    tolerance: float,
) -> float:
    if len(expected) != len(actual):
        raise RuntimeError(f"{name} length mismatch")
    maximum_delta = 0.0
    for index, (left, right) in enumerate(zip(expected, actual)):
        delta = abs(left - right)
        maximum_delta = max(maximum_delta, delta)
        if delta > tolerance:
            raise RuntimeError(
                f"{name}[{index}] mismatch: Kotlin={left}, Python={right}"
            )
    return maximum_delta


def compare(
    reviews: list[dict[str, Any]],
    kotlin: dict[str, Any],
) -> dict[str, Any]:
    examples, dataframe, optimizer = format_real(
        reviews,
        kotlin["timezone"],
        kotlin["dayStartHour"],
    )
    python_eligibility = eligibility(examples)
    if not python_eligibility["eligible"]:
        raise RuntimeError(f"Python rejected real history: {python_eligibility['reason']}")
    checks = {
        "activeQualifyingReviews": len(reviews),
        "historySha256": history_hash(reviews),
        "datasetSha256": canonical_hash(examples),
        "qualifyingReviews": python_eligibility["qualifyingReviews"],
        "qualifyingCards": python_eligibility["qualifyingCards"],
        "recalledOutcomes": python_eligibility["recalledOutcomes"],
        "forgottenOutcomes": python_eligibility["forgottenOutcomes"],
    }
    for name, actual in checks.items():
        if kotlin[name] != actual:
            raise RuntimeError(f"{name} mismatch: Kotlin={kotlin[name]}, Python={actual}")

    optimized = optimization_result(dataframe, optimizer)
    initialization_delta = close_list(
        "initializedParameters",
        kotlin["initializedParameters"],
        optimized["initializationParameters"],
        1e-4,
    )
    close_list(
        "parameters",
        kotlin["parameters"],
        optimized["optimized"]["parameters"],
        0.0,
    )
    metric_names = {
        "trainingExamples": "trainExamples",
        "validationExamples": "validationExamples",
        "trainingLossBefore": "trainingLossBefore",
        "trainingLossAfter": "trainingLossAfter",
        "validationLossBefore": "validationLossBefore",
        "validationLossAfter": "validationLossAfter",
    }
    metrics: dict[str, Any] = {}
    for kotlin_name, python_name in metric_names.items():
        expected = kotlin[kotlin_name]
        actual = optimized["optimized"][python_name]
        if expected != actual:
            raise RuntimeError(
                f"{kotlin_name} mismatch: Kotlin={expected}, Python={actual}"
            )
        metrics[kotlin_name] = actual
    return {
        **python_eligibility,
        **metrics,
        "initializationMaxAbsDelta": initialization_delta,
        "parameters": optimized["optimized"]["parameters"],
    }


def write_report(path: Path, kotlin: dict[str, Any], compared: dict[str, Any]) -> None:
    path.write_text(
        "\n".join(
            (
                "result=passed",
                "python_kotlin_mismatches=0",
                f"initialization_max_abs_delta={compared['initializationMaxAbsDelta']}",
                f"source_fingerprint_sha256={kotlin['sourceFingerprintSha256']}",
                f"timezone={kotlin['timezone']}",
                f"day_start_hour={kotlin['dayStartHour']}",
                f"active_cards={kotlin['activeCards']}",
                f"active_qualifying_reviews={kotlin['activeQualifyingReviews']}",
                f"optimizer_examples={compared['qualifyingReviews']}",
                f"optimizer_cards={compared['qualifyingCards']}",
                f"recalled_outcomes={compared['recalledOutcomes']}",
                f"forgotten_outcomes={compared['forgottenOutcomes']}",
                f"training_examples={compared['trainingExamples']}",
                f"validation_examples={compared['validationExamples']}",
                f"training_loss={compared['trainingLossBefore']}->{compared['trainingLossAfter']}",
                f"validation_loss={compared['validationLossBefore']}->{compared['validationLossAfter']}",
                "candidate_parameters=" + ",".join(map(str, compared["parameters"])),
            )
        )
        + "\n",
        encoding="utf-8",
    )


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: compare_real.py REVIEWS.tsv KOTLIN.json REPORT.txt")
    reviews_path, kotlin_path, report_path = map(Path, sys.argv[1:])
    reviews = read_reviews(reviews_path)
    kotlin = json.loads(kotlin_path.read_text(encoding="utf-8"))
    if kotlin["schemaVersion"] != 1:
        raise RuntimeError("unsupported Kotlin private-result schema")
    compared = compare(reviews, kotlin)
    write_report(report_path, kotlin, compared)
    print(
        f"matched {compared['qualifyingReviews']} examples over "
        f"{compared['qualifyingCards']} cards"
    )


if __name__ == "__main__":
    main()
