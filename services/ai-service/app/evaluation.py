"""Synthetic regression report; no ASR/language-validation claims are made."""
import asyncio
import json
from pathlib import Path
from app.providers.mock import MockProvider
from app.schema import ExtractionRequest


async def evaluate(path: Path):
    cases = json.loads(path.read_text())
    groups = {}
    for sample in cases:
        result = await MockProvider().extract(ExtractionRequest(transcript=sample["transcript"], language=sample["language"]))
        group = sample["language"] + ("/code-switch" if "switch" in sample["id"] else "/text")
        totals = groups.setdefault(group, {"fields": 0, "correct": 0, "cases": 0})
        totals["cases"] += 1
        for name, value in sample["expected"].items():
            totals["fields"] += 1
            actual = getattr(result, name).value
            totals["correct"] += actual is None if value is None else str(actual) == str(value)
    for totals in groups.values():
        totals["critical_field_accuracy"] = totals["correct"] / totals["fields"]
    return {"evaluation": "synthetic-regression-v1", "provider": "deterministic-fixture-parser", "production_validated": False,
            "speech_evaluation": "not_performed_requires_consented_audio_and_native_speaker_annotations", "groups": groups}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    print(json.dumps(asyncio.run(evaluate(parser.parse_args().dataset)), indent=2))
