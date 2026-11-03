#!/usr/bin/env python3
"""Generate deterministic FSRS optimizer dataset and training fixtures."""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from importlib.metadata import version
from pathlib import Path
from typing import Any, Iterator

import numpy as np
import pandas as pd
import torch
from fsrs_optimizer import (
    Collection,
    DEFAULT_PARAMETER,
    Optimizer,
    Trainer,
    lineToTensor,
    power_forgetting_curve,
)

BASE_TIME = datetime(2020, 1, 1, 12, tzinfo=timezone.utc)
DAY_MILLIS = 86_400_000
MINIMUM_REVIEWS = 1_000
MINIMUM_CARDS = 100
TIMEZONE = "UTC"
DAY_START_HOUR = 4
OPTIMIZER_COMMIT = "cf052b7843452369485f9d39f9ada562ba152ea8"
LOWER_BOUNDS = [
    0.001, 0.001, 0.001, 0.001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0,
    0.001, 0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.01, 0.1,
]
UPPER_BOUNDS = [
    100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8,
    3.5, 5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
]


@dataclass(frozen=True)
class CaseSpec:
    identifier: str
    cards: int
    reviews_per_card: int
    rating_mode: str
    timezone_name: str = TIMEZONE
    day_start_hour: int = DAY_START_HOUR
    boundary_times: bool = False


def rating_for(mode: str, card_index: int, review_index: int) -> int:
    if mode == "recalled":
        return 3
    if mode == "forgotten":
        return 1
    if review_index == 0:
        return card_index % 4 + 1
    selector = (card_index * 17 + review_index * 7) % 20
    if selector < 4:
        return 1
    if selector < 8:
        return 2
    if selector < 17:
        return 3
    return 4


def generate_reviews(spec: CaseSpec) -> list[dict[str, Any]]:
    intervals = [0, 1, 2, 4, 7, 14, 3, 21, 5, 30, 10, 45, 8, 60, 12, 90]
    reviews: list[dict[str, Any]] = []
    for card_index in range(spec.cards):
        card = f"card-{card_index + 1:04d}"
        if spec.boundary_times:
            current = datetime(2020, 1, 1, 3, 50, tzinfo=timezone.utc) + timedelta(
                seconds=card_index
            )
        else:
            current = BASE_TIME + timedelta(seconds=card_index)
        prior_rating = 3
        for review_index in range(spec.reviews_per_card):
            if review_index > 0:
                if spec.boundary_times:
                    current += timedelta(minutes=20) if review_index % 2 else timedelta(
                        hours=23, minutes=40
                    )
                else:
                    days = intervals[(review_index - 1) % len(intervals)]
                    current += timedelta(minutes=10) if days == 0 else timedelta(days=days)
            rating = rating_for(spec.rating_mode, card_index, review_index)
            reviewed_at = round(current.timestamp() * 1000)
            state = 0 if review_index == 0 else (3 if prior_rating == 1 else (1 if review_index == 1 else 2))
            reviews.append(
                {
                    "reviewId": reviewed_at,
                    "cardIdentity": card,
                    "reviewedAtMillis": reviewed_at,
                    "rating": rating,
                    "state": state,
                    "durationMillis": 2_500 + ((card_index * 31 + review_index * 101) % 8_000),
                }
            )
            prior_rating = rating
    return reviews


def history_hash(reviews: list[dict[str, Any]]) -> str:
    digest = hashlib.sha256()
    ordered = sorted(
        reviews,
        key=lambda item: (item["reviewId"], item["cardIdentity"]),
    )
    for review in ordered:
        identity = review["cardIdentity"].encode("utf-8")
        row = (
            f"{review['reviewId']}\n{len(identity)}:".encode("utf-8")
            + identity
            + f"\n{review['reviewedAtMillis']}\n{review['rating']}\n"
            f"{review['state']}\n{review['durationMillis']}\n".encode("utf-8")
        )
        digest.update(row)
    return digest.hexdigest()


def write_revlog(reviews: list[dict[str, Any]]) -> None:
    pd.DataFrame(
        {
            "card_id": [item["cardIdentity"] for item in reviews],
            "review_time": [item["reviewedAtMillis"] for item in reviews],
            "review_rating": [item["rating"] for item in reviews],
            "review_state": [item["state"] for item in reviews],
            "review_duration": [item["durationMillis"] for item in reviews],
        }
    ).to_csv("revlog.csv", index=False)


@contextmanager
def temporary_working_directory() -> Iterator[None]:
    original = Path.cwd()
    with tempfile.TemporaryDirectory(prefix="kelma-optimizer-") as directory:
        os.chdir(directory)
        try:
            yield
        finally:
            os.chdir(original)


def parse_history(value: Any) -> list[int]:
    text = str(value)
    if not text or text == "nan":
        return []
    return [int(float(item)) for item in text.split(",") if item != ""]


def format_examples(dataframe: pd.DataFrame) -> list[dict[str, Any]]:
    training = dataframe[(dataframe["i"] > 1) & (dataframe["delta_t"] > 0)].copy()
    training.sort_values(by=["review_time", "card_id"], inplace=True)
    return [
        {
            "reviewId": int(row.review_time),
            "cardIdentity": str(row.card_id),
            "reviewedAtMillis": int(row.review_time),
            "rating": int(row.review_rating),
            "elapsedDays": int(row.delta_t),
            "historyElapsedDays": parse_history(row.t_history),
            "historyRatings": parse_history(row.r_history),
            "sequence": int(row.i),
            "lastCrossDayRating": int(row.last_rating),
            "recalled": int(row.y) == 1,
        }
        for row in training.itertuples(index=False)
    ]


def canonical_hash(value: Any) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def eligibility(examples: list[dict[str, Any]]) -> dict[str, Any]:
    cards = len({item["cardIdentity"] for item in examples})
    recalled = sum(1 for item in examples if item["recalled"])
    forgotten = len(examples) - recalled
    reason = None
    if len(examples) < MINIMUM_REVIEWS:
        reason = "insufficient_reviews"
    elif cards < MINIMUM_CARDS:
        reason = "insufficient_cards"
    elif forgotten == 0:
        reason = "missing_forgotten_outcome"
    elif recalled == 0:
        reason = "missing_recalled_outcome"
    return {
        "eligible": reason is None,
        "reason": reason,
        "qualifyingReviews": len(examples),
        "qualifyingCards": cards,
        "recalledOutcomes": recalled,
        "forgottenOutcomes": forgotten,
        "minimumReviews": MINIMUM_REVIEWS,
        "minimumCards": MINIMUM_CARDS,
    }


def format_case(spec: CaseSpec) -> tuple[dict[str, Any], pd.DataFrame, Optimizer]:
    reviews = generate_reviews(spec)
    with temporary_working_directory():
        write_revlog(reviews)
        optimizer = Optimizer(float_delta_t=False, enable_short_term=True)
        optimizer.create_time_series(
            spec.timezone_name,
            "2006-01-01",
            spec.day_start_hour,
            analysis=False,
        )
        dataframe = pd.read_csv(
            "revlog_history.tsv",
            sep="\t",
            dtype={"r_history": str, "t_history": str},
        )
        examples = format_examples(dataframe)
        initialization = (
            dataframe[dataframe["i"] == 2]
            .groupby(by=["first_rating", "delta_t"], group_keys=False)
            .agg(recalledRate=("y", "mean"), count=("y", "count"))
            .reset_index()
            .sort_values(by=["first_rating", "delta_t"])
        )
        initialization_rows = [
            {
                "firstRating": int(row.first_rating),
                "elapsedDays": int(row.delta_t),
                "recalledRate": float(row.recalledRate),
                "count": int(row.count),
            }
            for row in initialization.itertuples(index=False)
        ]
    value = {
        "id": spec.identifier,
        "timezone": spec.timezone_name,
        "dayStartHour": spec.day_start_hour,
        "historySha256": history_hash(reviews),
        "datasetSha256": canonical_hash(examples),
        "rawReviews": reviews,
        "examples": examples,
        "initializationRows": initialization_rows,
        "eligibility": eligibility(examples),
    }
    return value, dataframe, optimizer


def add_tensors(dataframe: pd.DataFrame) -> pd.DataFrame:
    result = dataframe.copy()
    result["tensor"] = result.apply(
        lambda row: lineToTensor((str(row["t_history"]), str(row["r_history"]))),
        axis=1,
    )
    result.sort_values(by=["review_time", "card_id"], inplace=True)
    positions = np.linspace(0, 1, len(result))
    result["weights"] = 0.25 + 0.75 * np.power(positions, 3)
    return result


def log_loss_for(dataframe: pd.DataFrame, parameters: list[float]) -> float:
    collection = Collection(parameters, float_delta_t=False)
    stabilities, _ = collection.batch_predict(dataframe)
    predictions = power_forgetting_curve(
        dataframe["delta_t"].to_numpy(),
        np.array(stabilities),
        -parameters[20],
    )
    predictions = np.clip(np.asarray(predictions, dtype=float), 1e-7, 1 - 1e-7)
    labels = dataframe["y"].to_numpy(dtype=float)
    losses = -(labels * np.log(predictions) + (1 - labels) * np.log(1 - predictions))
    return float(np.average(losses, weights=dataframe["weights"].to_numpy(dtype=float)))


class PortableBatchLoader:
    """Use a shuffle whose exact integer behavior is portable to Kotlin/Native."""

    def __init__(self, dataset: Any, seed: int):
        self.dataset = dataset
        self.batch_nums = len(dataset)
        self.state = seed & 0xFFFFFFFF
        self.orders: list[list[int]] = []

    def __iter__(self) -> Iterator[Any]:
        order = list(range(self.batch_nums))
        for index in range(len(order) - 1, 0, -1):
            self.state = (1664525 * self.state + 1013904223) & 0xFFFFFFFF
            selected = self.state % (index + 1)
            order[index], order[selected] = order[selected], order[index]
        self.orders.append(order)
        yield from (self.dataset[index] for index in order)


def train_once(dataframe: pd.DataFrame, initial: list[float]) -> dict[str, Any]:
    prepared = add_tensors(dataframe)
    split = int(len(prepared) * 0.8)
    train_set = prepared.iloc[:split].copy()
    validation_set = prepared.iloc[split:].copy()
    trainer = Trainer(
        train_set,
        validation_set,
        initial.copy(),
        n_epoch=3,
        lr=4e-2,
        gamma=1.0,
        batch_size=128,
        max_seq_len=64,
        float_delta_t=False,
        enable_short_term=True,
    )
    loader = PortableBatchLoader(trainer.train_set, seed=2023)
    trainer.train_data_loader = loader
    trained = trainer.train(verbose=False)
    optimized = [round(float(value), 4) for value in trained.tolist()]
    # CPU reductions differ by a few nanounits across x86 implementations.
    # Six decimals preserves useful metrics while making fixture bytes stable.
    return {
        "parameters": optimized,
        "trainExamples": len(train_set),
        "validationExamples": len(validation_set),
        "batchOrders": loader.orders,
        "trainingLossBefore": round(log_loss_for(train_set, DEFAULT_PARAMETER), 6),
        "trainingLossAfter": round(log_loss_for(train_set, optimized), 6),
        "validationLossBefore": round(log_loss_for(validation_set, DEFAULT_PARAMETER), 6),
        "validationLossAfter": round(log_loss_for(validation_set, optimized), 6),
    }


def study_day_vectors() -> list[dict[str, Any]]:
    inputs = [
        (
            "utc-day-start",
            "UTC",
            4,
            [
                "2024-01-01T03:50:00Z",
                "2024-01-01T04:10:00Z",
                "2024-01-02T03:50:00Z",
                "2024-01-02T04:10:00Z",
            ],
        ),
        (
            "new-york-dst",
            "America/New_York",
            4,
            [
                "2024-03-09T09:30:00Z",
                "2024-03-10T08:30:00Z",
                "2024-03-11T08:30:00Z",
                "2024-11-02T08:30:00Z",
                "2024-11-03T09:30:00Z",
            ],
        ),
    ]
    result: list[dict[str, Any]] = []
    for identifier, timezone_name, day_start_hour, timestamps in inputs:
        study_days = [
            (
                pd.Timestamp(value)
                .tz_convert(timezone_name)
                - pd.Timedelta(hours=day_start_hour)
            ).date().isoformat()
            for value in timestamps
        ]
        ordinals = [datetime.fromisoformat(value).date().toordinal() for value in study_days]
        result.append(
            {
                "id": identifier,
                "timezone": timezone_name,
                "dayStartHour": day_start_hour,
                "reviewedAtMillis": [round(pd.Timestamp(value).timestamp() * 1000) for value in timestamps],
                "studyDays": study_days,
                "elapsedDays": [0] + [right - left for left, right in zip(ordinals, ordinals[1:])],
            }
        )
    return result


def optimization_result(dataframe: pd.DataFrame, optimizer: Optimizer) -> dict[str, Any]:
    with temporary_working_directory():
        optimizer.define_model()
        optimizer.dataset = dataframe.copy()
        optimizer.dataset_for_initialization = (
            optimizer.dataset[optimizer.dataset["i"] == 2]
            .groupby(by=["first_rating", "delta_t"], group_keys=False)
            .agg({"y": ["mean", "count"]})
            .reset_index()
        )
        optimizer.initialize_parameters(dataset=optimizer.dataset.copy(), verbose=False)
        initial = [round(float(value), 12) for value in optimizer.init_w]
        first = train_once(optimizer.dataset.copy(), optimizer.init_w)
        second = train_once(optimizer.dataset.copy(), optimizer.init_w)
    if first != second:
        raise RuntimeError("Pinned optimizer run is not deterministic")
    if len(first["parameters"]) != 21:
        raise RuntimeError("Optimizer did not return 21 parameters")
    for index, value in enumerate(first["parameters"]):
        if not LOWER_BOUNDS[index] <= value <= UPPER_BOUNDS[index]:
            raise RuntimeError(f"parameter {index} outside pinned bounds")
    return {
        "initializationParameters": initial,
        "optimized": first,
        "deterministicRepeatMatches": True,
        "parameterBoundsSatisfied": True,
    }


def main() -> None:
    if len(os.sys.argv) != 2:
        raise SystemExit("usage: generate.py OUTPUT")
    torch.set_num_threads(1)
    torch.set_num_interop_threads(1)
    torch.use_deterministic_algorithms(True)

    specs = [
        CaseSpec("balanced-training", 120, 14, "balanced"),
        CaseSpec("insufficient-reviews", 80, 8, "balanced"),
        CaseSpec("insufficient-cards", 80, 16, "balanced"),
        CaseSpec("missing-forgotten", 120, 14, "recalled"),
        CaseSpec("missing-recalled", 120, 14, "forgotten"),
        CaseSpec("day-start-boundary", 80, 6, "balanced", boundary_times=True),
    ]
    cases: list[dict[str, Any]] = []
    balanced_frame: pd.DataFrame | None = None
    balanced_optimizer: Optimizer | None = None
    for spec in specs:
        value, dataframe, optimizer = format_case(spec)
        cases.append(value)
        if spec.identifier == "balanced-training":
            balanced_frame = dataframe
            balanced_optimizer = optimizer

    if balanced_frame is None or balanced_optimizer is None:
        raise RuntimeError("balanced fixture missing")
    optimization = optimization_result(balanced_frame, balanced_optimizer)
    payload = {
        "schemaVersion": 2,
        "oracle": {
            "package": "FSRS-Optimizer",
            "packageVersion": version("FSRS-Optimizer"),
            "releaseCommit": OPTIMIZER_COMMIT,
            "license": "BSD-3-Clause",
            "wheelSha256": "245ea144584c3db2bbdfdccb3beebf29978a400aa01f8cd33bf947b6f7cff20c",
            "torchVersion": version("torch"),
            "lockedDependencies": {
                name: version(name)
                for name in (
                    "numpy",
                    "pandas",
                    "scipy",
                    "scikit-learn",
                    "statsmodels",
                    "matplotlib",
                )
            },
            "requirementsSha256": hashlib.sha256(
                Path("/oracle/requirements.txt").read_bytes()
            ).hexdigest(),
            "pythonVersion": os.sys.version.split()[0],
            "containerImage": "python:3.13.12-slim",
            "containerDigest": "sha256:f1927c75e81efd1e091dbd64b6c0ecaa5630b38635a3d1c04034ac636e1f94c8",
            "containerPlatform": "linux/amd64",
        },
        "trainingPolicy": {
            "seed": 2023,
            "shuffle": "lcg32-fisher-yates",
            "shuffleMultiplier": 1664525,
            "shuffleIncrement": 1013904223,
            "split": "chronological-80-20",
            "epochs": 3,
            "learningRate": 0.04,
            "regularizationGamma": 1.0,
            "batchSize": 128,
            "maximumSequenceLength": 64,
            "recencyWeights": "0.25 + 0.75 * position^3",
        },
        "datasetContract": {
            "reviewOrder": ["cardIdentity", "reviewedAtMillis", "reviewId"],
            "studyDay": "local date of reviewedAtMillis minus dayStartHour",
            "elapsedDays": "difference between consecutive study-day ordinals",
            "sameDayTargets": "excluded while retained in prior history",
            "outcome": "rating Again is forgotten; Hard, Good, and Easy are recalled",
            "historyHash": "reviewId\\nbyteLength:cardIdentity\\nreviewedAtMillis\\nrating\\nstate\\ndurationMillis\\n",
            "eligibility": {
                "minimumQualifyingReviews": MINIMUM_REVIEWS,
                "minimumQualifyingCards": MINIMUM_CARDS,
                "requiresRecalled": True,
                "requiresForgotten": True,
            },
        },
        "parameterBounds": {"lower": LOWER_BOUNDS, "upper": UPPER_BOUNDS},
        "studyDayVectors": study_day_vectors(),
        "cases": cases,
        "optimization": {"caseId": "balanced-training", **optimization},
        "cancellationContract": {
            "checkpoints": [
                "before_formatting",
                "after_formatting",
                "between_epochs",
                "before_candidate_publication",
            ],
            "expectedStatus": "cancelled",
            "activeParametersAfterCancellation": DEFAULT_PARAMETER,
            "candidateAfterCancellation": None,
        },
    }
    output = Path(os.sys.argv[1])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {len(cases)} optimizer cases to {output}")


if __name__ == "__main__":
    main()
