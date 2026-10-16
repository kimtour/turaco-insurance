"""Synthetic regression report; no ASR/language-validation claims are made."""
import asyncio
import json
from pathlib import Path
from app.providers.mock import MockProvider
from app.schema import ExtractionRequest


async def evaluate(path: Path):
    cases = json.loads(path.read_text())
    if not isinstance(cases, list) or not cases:
        raise ValueError("Evaluation requires a non-empty synthetic dataset")
    groups = {}
    for sample in cases:
        result = await MockProvider().extract(ExtractionRequest(transcript=sample["transcript"], language=sample["language"]))
        group = sample["language"] + ("/code-switch" if "switch" in sample["id"] else "/text")
        totals = groups.setdefault(group, {"fields": 0, "correct": 0, "cases": 0,
                                           "true_positive": 0, "false_positive": 0, "false_negative": 0})
        totals["cases"] += 1
        for name, value in sample["expected"].items():
            totals["fields"] += 1
            actual = getattr(result, name).value
            correct = actual is None if value is None else str(actual) == str(value)
            totals["correct"] += correct
            totals["true_positive"] += value is not None and correct
            totals["false_positive"] += actual is not None and not correct
            totals["false_negative"] += value is not None and not correct
        if not sample["expected"]:
            raise ValueError("Every case requires expected critical fields")
    for totals in groups.values():
        totals["critical_field_accuracy"] = totals["correct"] / totals["fields"]
        totals["critical_field_error_rate"] = 1 - totals["critical_field_accuracy"]
        tp, fp, fn = (totals[key] for key in ("true_positive", "false_positive", "false_negative"))
        totals["precision"] = tp / (tp + fp) if tp + fp else None
        totals["recall"] = tp / (tp + fn) if tp + fn else None
    return {"evaluation": "synthetic-regression-v1", "provider": "deterministic-fixture-parser", "production_validated": False,
            "speech_evaluation": "not_performed_requires_consented_audio_and_native_speaker_annotations", "groups": groups}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--check", action="store_true", help="Fail when any deterministic synthetic expectation differs")
    args = parser.parse_args()
    report = asyncio.run(evaluate(args.dataset))
    print(json.dumps(report, indent=2))
    if args.check and any(group["correct"] != group["fields"] for group in report["groups"].values()):
        raise SystemExit(1)
