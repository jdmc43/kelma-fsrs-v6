# FSRS optimizer oracle

This development-only tool generates the deterministic optimizer dataset and
training fixture from `fsrs-optimizer==6.5.0`.

## Pins

- Package: `FSRS-Optimizer==6.5.0`
- Release commit: `cf052b7843452369485f9d39f9ada562ba152ea8`
- Wheel SHA-256:
  `245ea144584c3db2bbdfdccb3beebf29978a400aa01f8cd33bf947b6f7cff20c`
- License: BSD-3-Clause
- PyTorch: `2.9.1+cpu`
- Base image: `python:3.13.12-slim`
- Base digest:
  `sha256:f1927c75e81efd1e091dbd64b6c0ecaa5630b38635a3d1c04034ac636e1f94c8`
- Canonical platform: `linux/amd64`

Every Python dependency is version-locked and hash-checked in
`requirements.txt`. `requirements.in` is retained to make intentional lock
updates reviewable. Python, PyTorch, pandas, and the optimizer package are not
library or application dependencies.

## Generate

```bash
./tools/optimizer_oracle/generate.sh
./tools/verify_optimizer_fixture.py
```

The output is:

```text
testdata/fsrs-optimizer-6.5.0-vectors.json
```

Canonical fixture SHA-256:

```text
6e7bbe6ef2b0e13cb2fd869017fe1dc4e5903163cfeb6f5577467ca9af87453a
```

The generator runs the successful training case twice with the portable
LCG32/Fisher–Yates batch order and refuses differing results. PyTorch still
provides the pinned FSRS model, loss, Adam updates, and clipping. The standalone
verifier needs only Python's standard library and checks
the fixture hash, package provenance, dataset/history hashes, eligibility
outcomes, parameter bounds, loss improvement, determinism, cancellation, and
the absence of unexpected raw-review fields.

See [`OPTIMIZER_DATASET.md`](../../OPTIMIZER_DATASET.md) for the portable Kotlin
input and lifecycle contract.

## Updating

An oracle package, dependency, container, training-policy, or dataset-format
change requires:

1. a new fixture schema or filename;
2. an intentional requirements lock update;
3. regenerated output on pinned `linux/amd64`;
4. review of parameter and loss changes;
5. updated provenance, notices, and pinned verifier hash.

Do not edit expected parameters or losses by hand.

## Private real-history comparison

`compare_real.py` accepts a temporary content-free review TSV and a Kotlin
machine result, reproduces formatting and optimization with the pinned oracle,
and requires exact dataset hashes, rounded candidate parameters, split counts,
and six-decimal metrics. Initialized parameters allow `1e-4` for SciPy's
scalar-minimizer stopping behavior, and the maximum difference is reported. It is invoked by the client repository's
`run-real-collection-optimizer-acceptance.sh`; private inputs remain in a
trap-cleaned temporary directory and must never be committed.
