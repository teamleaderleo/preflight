#!/usr/bin/env python3
"""Fit run-blocked combat scaling laws from CombatWorkloadRuntime reports.

Discovery timing and final frame-rate measurement stay separate. This fitter consumes the sampled
CombatEngine.advance timings paired with workload snapshots, aggregates them into time buckets, and
compares constant, linear, N log N, quadratic, threshold, and selected interaction models. When at
least three independent runs are present, model ranking uses leave-one-run-out RMSE; smaller
datasets fall back to AICc and are labeled exploratory.
"""
from __future__ import annotations

import argparse
import collections
import json
import math
import pathlib
import statistics
from dataclasses import dataclass
from typing import Callable, Iterable


TARGET = "advanceMicros"
BASE_PREDICTORS = (
    "battleDp",
    "liveDeployedDp",
    "shipDpPresent",
    "ships",
    "fighters",
    "wrecks",
    "missiles",
    "projectiles",
    "beams",
    "shipAi",
    "fighterAi",
    "missileAi",
    "weapons",
    "firingWeapons",
    "beamWeapons",
    "weaponEffectPlugins",
    "effectLikeObjectsHeuristic",
    "internalCollectionTotal",
    "nearbyEntitiesMean",
    "nearbyShipsMean",
    "nearbyMissilesMean",
    "combatElapsedSeconds",
)


@dataclass(frozen=True)
class Term:
    name: str
    value: Callable[[dict], float]


@dataclass(frozen=True)
class Model:
    name: str
    kind: str
    terms: tuple[Term, ...]
    predictors: tuple[str, ...]


@dataclass
class Fit:
    model: Model
    coefficients: list[float]
    means: list[float]
    scales: list[float]
    rmse: float
    r2: float
    aicc: float
    cv_rmse: float | None
    observations: int

    @property
    def score(self) -> float:
        return self.cv_rmse if self.cv_rmse is not None else self.aicc


@dataclass(frozen=True)
class Analysis:
    rows: list[dict]
    fits: list[Fit]
    runs: tuple[str, ...]
    cells: tuple[str, ...]
    evidence: str
    best_linear_score: float | None
    confirmed_superlinear: bool


def numeric(value) -> float | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, (int, float)) and math.isfinite(float(value)):
        return float(value)
    return None


def load_reports(paths: Iterable[str | pathlib.Path]) -> list[dict]:
    rows = []
    for raw_path in paths:
        path = pathlib.Path(raw_path)
        payload = json.loads(path.read_text(encoding="utf-8"))
        if "combatWorkload" in payload:
            payload = payload["combatWorkload"]
        run_id = str(payload.get("runId") or path.stem)
        cell_id = str(payload.get("cellId") or "unspecified")
        report_battle_dp = numeric(payload.get("battleDp"))
        for sample in payload.get("samples") or []:
            if numeric(sample.get(TARGET)) is None:
                continue
            row = dict(sample)
            if numeric(row.get("battleDp")) is None and report_battle_dp is not None:
                row["battleDp"] = report_battle_dp
            row["runId"] = run_id
            row["cellId"] = cell_id
            row["source"] = str(path)
            row["totalAi"] = sum(numeric(row.get(name)) or 0.0 for name in (
                "shipAi", "fighterAi", "missileAi"))
            row["ordnance"] = sum(numeric(row.get(name)) or 0.0 for name in (
                "missiles", "projectiles", "beams"))
            row["effects"] = max(
                numeric(row.get("weaponEffectPlugins")) or 0.0,
                numeric(row.get("effectLikeObjectsHeuristic")) or 0.0,
            )
            rows.append(row)
    return rows


def bucket_rows(rows: list[dict], seconds: float = 10.0) -> list[dict]:
    grouped = collections.defaultdict(list)
    for row in rows:
        elapsed = numeric(row.get("combatElapsedSeconds")) or 0.0
        bucket = int(elapsed // max(seconds, 0.001))
        key = (row["runId"], row["cellId"], int(row.get("battleId") or 0), bucket)
        grouped[key].append(row)

    result = []
    for (run_id, cell_id, battle_id, bucket), values in sorted(grouped.items()):
        merged = {
            "runId": run_id,
            "cellId": cell_id,
            "battleId": battle_id,
            "bucket": bucket,
            "samples": len(values),
        }
        names = set(BASE_PREDICTORS) | {TARGET, "totalAi", "ordnance", "effects"}
        for name in names:
            observed = [value for row in values if (value := numeric(row.get(name))) is not None]
            if observed:
                merged[name] = statistics.median(observed)
        result.append(merged)
    return result


def term(name: str) -> Term:
    return Term(name, lambda row, n=name: float(row.get(n, 0.0)))


def transformed(name: str, suffix: str, transform: Callable[[float], float]) -> Term:
    return Term(f"{name}_{suffix}", lambda row, n=name, f=transform: f(float(row.get(n, 0.0))))


def interaction(left: str, right: str) -> Term:
    return Term(f"{left}_x_{right}",
                lambda row, a=left, b=right: float(row.get(a, 0.0)) * float(row.get(b, 0.0)))


def hinge(name: str, threshold: float) -> Term:
    return Term(
        f"{name}_above_{threshold:g}",
        lambda row, n=name, t=threshold: max(0.0, float(row.get(n, 0.0)) - t),
    )


def threshold_candidates(rows: list[dict], name: str) -> list[float]:
    values = sorted({float(row[name]) for row in rows if numeric(row.get(name)) is not None})
    if len(values) < 4:
        return []
    indexes = set()
    for quantile in (0.25, 0.50, 0.75):
        index = round((len(values) - 1) * quantile)
        index = max(1, min(len(values) - 2, index))
        indexes.add(index)
    return [values[index] for index in sorted(indexes)]


def candidate_models(rows: list[dict]) -> list[Model]:
    available = [name for name in BASE_PREDICTORS + ("totalAi", "ordnance", "effects")
                 if varied(rows, name)]
    models = [Model("constant", "constant", (), ())]
    for name in available:
        models.append(Model(f"{name}:linear", "linear", (term(name),), (name,)))
        for threshold in threshold_candidates(rows, name):
            models.append(Model(
                f"{name}:threshold@{threshold:g}",
                "threshold",
                (term(name), hinge(name, threshold)),
                (name,),
            ))
        if nonnegative(rows, name):
            models.append(Model(
                f"{name}:nlogn", "nlogn",
                (transformed(name, "nlogn", lambda x: x * math.log2(max(2.0, x))),),
                (name,)))
            models.append(Model(
                f"{name}:quadratic", "quadratic",
                (transformed(name, "squared", lambda x: x * x),),
                (name,)))

    density = "nearbyEntitiesMean"
    if density in available:
        for name in ("battleDp", "liveDeployedDp", "ships", "fighters", "wrecks", "missiles",
                     "projectiles", "beams", "totalAi", "effects", "ordnance"):
            if name in available:
                models.append(Model(
                    f"{name}*{density}", "interaction",
                    (term(name), term(density), interaction(name, density)),
                    (name, density)))

    duration = "combatElapsedSeconds"
    if duration in available:
        for name in ("wrecks", "effects", "internalCollectionTotal", "shipDpPresent"):
            if name in available:
                models.append(Model(
                    f"{duration}*{name}", "interaction",
                    (term(duration), term(name), interaction(duration, name)),
                    (duration, name)))

    for left, right in (("ships", "fighters"), ("missiles", "projectiles"),
                        ("fighters", "missiles"), ("beams", "effects")):
        if left in available and right in available:
            models.append(Model(
                f"{left}*{right}", "interaction",
                (term(left), term(right), interaction(left, right)),
                (left, right)))

    mixed = [name for name in (
        "battleDp", "liveDeployedDp", "ships", "fighters", "wrecks", "missiles", "projectiles",
        "beams", "totalAi", "effects", "nearbyEntitiesMean", "combatElapsedSeconds")
             if name in available]
    if 2 <= len(mixed) <= max(2, len(rows) // 4):
        models.append(Model("entity-mix:linear", "multivariate-linear",
                            tuple(term(name) for name in mixed), tuple(mixed)))
    return models


def varied(rows: list[dict], name: str) -> bool:
    values = {round(float(row[name]), 12) for row in rows if numeric(row.get(name)) is not None}
    return len(values) >= 2


def nonnegative(rows: list[dict], name: str) -> bool:
    values = [numeric(row.get(name)) for row in rows]
    values = [value for value in values if value is not None]
    return bool(values) and min(values) >= 0.0


def solve(matrix: list[list[float]], vector: list[float]) -> list[float] | None:
    n = len(vector)
    augmented = [list(matrix[row]) + [vector[row]] for row in range(n)]
    for column in range(n):
        pivot = max(range(column, n), key=lambda row: abs(augmented[row][column]))
        if abs(augmented[pivot][column]) < 1e-12:
            return None
        augmented[column], augmented[pivot] = augmented[pivot], augmented[column]
        scale = augmented[column][column]
        augmented[column] = [value / scale for value in augmented[column]]
        for row in range(n):
            if row == column:
                continue
            factor = augmented[row][column]
            if factor == 0.0:
                continue
            augmented[row] = [
                augmented[row][index] - factor * augmented[column][index]
                for index in range(n + 1)
            ]
    return [augmented[row][-1] for row in range(n)]


def fit_coefficients(rows: list[dict], model: Model) -> tuple[list[float], list[float], list[float]] | None:
    if len(rows) <= len(model.terms) + 1:
        return None
    raw_columns = [[model_term.value(row) for row in rows] for model_term in model.terms]
    means = [statistics.fmean(column) for column in raw_columns]
    scales = []
    for column, mean in zip(raw_columns, means):
        variance = statistics.fmean((value - mean) ** 2 for value in column)
        scales.append(math.sqrt(variance) if variance > 1e-18 else 1.0)

    x = []
    for index, row in enumerate(rows):
        x.append([1.0] + [
            (raw_columns[column][index] - means[column]) / scales[column]
            for column in range(len(model.terms))
        ])
    y = [float(row[TARGET]) for row in rows]
    width = len(model.terms) + 1
    gram = [[0.0] * width for _ in range(width)]
    rhs = [0.0] * width
    for values, target in zip(x, y):
        for left in range(width):
            rhs[left] += values[left] * target
            for right in range(width):
                gram[left][right] += values[left] * values[right]
    for index in range(1, width):
        gram[index][index] += 1e-10
    coefficients = solve(gram, rhs)
    return None if coefficients is None else (coefficients, means, scales)


def predict(row: dict, model: Model, coefficients: list[float], means: list[float], scales: list[float]) -> float:
    value = coefficients[0]
    for index, model_term in enumerate(model.terms):
        normalized = (model_term.value(row) - means[index]) / scales[index]
        value += coefficients[index + 1] * normalized
    return value


def metrics(rows: list[dict], model: Model, coefficients: list[float], means: list[float], scales: list[float]):
    actual = [float(row[TARGET]) for row in rows]
    predicted = [predict(row, model, coefficients, means, scales) for row in rows]
    errors = [left - right for left, right in zip(actual, predicted)]
    sse = sum(error * error for error in errors)
    rmse = math.sqrt(sse / len(rows))
    mean = statistics.fmean(actual)
    sst = sum((value - mean) ** 2 for value in actual)
    r2 = 1.0 - sse / sst if sst > 1e-18 else 0.0
    k = len(model.terms) + 1
    n = len(rows)
    safe_sse = max(sse, 1e-18)
    aic = n * math.log(safe_sse / n) + 2 * k
    aicc = aic + (2 * k * (k + 1) / (n - k - 1)) if n > k + 1 else math.inf
    return rmse, r2, aicc


def cross_validated_rmse(rows: list[dict], model: Model, runs: list[str]) -> float | None:
    if len(runs) < 3:
        return None
    squared = []
    for held_out in runs:
        train = [row for row in rows if row["runId"] != held_out]
        test = [row for row in rows if row["runId"] == held_out]
        fitted = fit_coefficients(train, model)
        if fitted is None or not test:
            return None
        coefficients, means, scales = fitted
        for row in test:
            error = float(row[TARGET]) - predict(row, model, coefficients, means, scales)
            squared.append(error * error)
    return math.sqrt(statistics.fmean(squared)) if squared else None


def fit_model(rows: list[dict], model: Model, runs: list[str]) -> Fit | None:
    fitted = fit_coefficients(rows, model)
    if fitted is None:
        return None
    coefficients, means, scales = fitted
    rmse, r2, aicc = metrics(rows, model, coefficients, means, scales)
    return Fit(model, coefficients, means, scales, rmse, r2, aicc,
               cross_validated_rmse(rows, model, runs), len(rows))


def worsening_nonlinearity(fit: Fit) -> bool:
    if fit.model.kind not in ("nlogn", "quadratic", "threshold", "interaction"):
        return False
    return len(fit.coefficients) >= 2 and fit.coefficients[-1] > 0.0


def analyze(rows: list[dict]) -> Analysis:
    usable = [row for row in rows if numeric(row.get(TARGET)) is not None]
    runs = sorted({str(row["runId"]) for row in usable})
    cells = sorted({str(row["cellId"]) for row in usable})
    fits = [fit for model in candidate_models(usable)
            if (fit := fit_model(usable, model, runs)) is not None]
    fits.sort(key=lambda fit: (fit.score, fit.model.name))
    evidence = "repeatable" if len(runs) >= 3 and len(cells) >= 4 and len(usable) >= 16 else "exploratory"
    linear_scores = [fit.score for fit in fits if fit.model.kind in ("linear", "multivariate-linear")]
    best_linear = min(linear_scores) if linear_scores else None
    best = fits[0] if fits else None
    confirmed = bool(
        evidence == "repeatable"
        and best is not None
        and worsening_nonlinearity(best)
        and best.cv_rmse is not None
        and best_linear is not None
        and best.score <= best_linear * 0.90
    )
    return Analysis(usable, fits, tuple(runs), tuple(cells), evidence, best_linear, confirmed)


def range_summary(rows: list[dict]) -> dict[str, dict[str, float]]:
    result = {}
    for name in BASE_PREDICTORS + ("totalAi", "ordnance", "effects", TARGET):
        values = [value for row in rows if (value := numeric(row.get(name))) is not None]
        if values:
            ordered = sorted(values)
            result[name] = {
                "min": ordered[0],
                "median": statistics.median(ordered),
                "max": ordered[-1],
            }
    return result


def fit_to_dict(fit: Fit) -> dict:
    return {
        "model": fit.model.name,
        "kind": fit.model.kind,
        "predictors": list(fit.model.predictors),
        "terms": [model_term.name for model_term in fit.model.terms],
        "observations": fit.observations,
        "rmseMicros": fit.rmse,
        "r2": fit.r2,
        "aicc": fit.aicc,
        "leaveOneRunOutRmseMicros": fit.cv_rmse,
        "standardizedCoefficients": fit.coefficients,
        "worseningNonlinearity": worsening_nonlinearity(fit),
    }


def analysis_to_dict(analysis: Analysis, bucket_seconds: float, sources: list[str]) -> dict:
    best = analysis.fits[0] if analysis.fits else None
    return {
        "target": TARGET,
        "bucketSeconds": bucket_seconds,
        "sources": sources,
        "runs": list(analysis.runs),
        "cells": list(analysis.cells),
        "observations": len(analysis.rows),
        "evidence": analysis.evidence,
        "selectedModel": best.model.name if best else None,
        "selectedKind": best.model.kind if best else None,
        "confirmedSuperlinear": analysis.confirmed_superlinear,
        "confirmedBadScaling": analysis.confirmed_superlinear,
        "ranges": range_summary(analysis.rows),
        "models": [fit_to_dict(fit) for fit in analysis.fits],
    }


def render_markdown(result: dict, limit: int = 12) -> str:
    lines = [
        "# Combat scaling fit",
        "",
        f"Target: `{result['target']}` from sampled `CombatEngine.advance` ticks.",
        f"Evidence: **{result['evidence']}**; runs={len(result['runs'])}, "
        f"cells={len(result['cells'])}, bucketed observations={result['observations']}.",
        f"Selected model: **{result['selectedModel'] or 'none'}**.",
        f"Confirmed materially bad nonlinear/threshold/interaction path: "
        f"**{str(result['confirmedBadScaling']).lower()}**.",
        "",
        "Discovery rule: final player-facing FPS claims still require the independent frame-time cohort.",
        "",
        "## Top models",
        "",
        "| rank | model | kind | run-blocked RMSE (µs) | fit RMSE (µs) | R² | AICc | worsening |",
        "| ---: | --- | --- | ---: | ---: | ---: | ---: | --- |",
    ]
    for index, model in enumerate(result["models"][:limit], 1):
        cv = model["leaveOneRunOutRmseMicros"]
        lines.append(
            f"| {index} | `{model['model']}` | {model['kind']} | "
            f"{format_number(cv)} | {format_number(model['rmseMicros'])} | "
            f"{format_number(model['r2'])} | {format_number(model['aicc'])} | "
            f"{str(model['worseningNonlinearity']).lower()} |")
    lines.extend(["", "## Observed ranges", "",
                  "| predictor | min | median | max |", "| --- | ---: | ---: | ---: |"])
    for name, values in result["ranges"].items():
        lines.append(f"| `{name}` | {format_number(values['min'])} | "
                     f"{format_number(values['median'])} | {format_number(values['max'])} |")
    lines.append("")
    return "\n".join(lines)


def format_number(value) -> str:
    if value is None:
        return "—"
    if not math.isfinite(float(value)):
        return "∞"
    return f"{float(value):.3f}"


def report(paths: list[str], bucket_seconds: float = 10.0) -> dict:
    raw = load_reports(paths)
    bucketed = bucket_rows(raw, seconds=bucket_seconds)
    return analysis_to_dict(analyze(bucketed), bucket_seconds, paths)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("reports", nargs="+", help="combat workload JSON reports")
    parser.add_argument("--bucket-seconds", type=float, default=10.0,
                        help="median aggregation window inside each battle (default: 10)")
    parser.add_argument("--json-out", help="write machine-readable fit results")
    parser.add_argument("--markdown-out", help="write issue-ready Markdown fit summary")
    parser.add_argument("--top", type=int, default=12, help="models printed in Markdown")
    args = parser.parse_args()

    result = report(args.reports, bucket_seconds=args.bucket_seconds)
    rendered = render_markdown(result, limit=args.top)
    print(rendered)
    if args.json_out:
        pathlib.Path(args.json_out).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n",
                                               encoding="utf-8")
    if args.markdown_out:
        pathlib.Path(args.markdown_out).write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
