import asyncio
import json
from pathlib import Path
import subprocess
import sys

import pytest
from app.evaluation import evaluate

DATASET = Path(__file__).resolve().parents[3] / 'datasets/eval/hospital-cash.json'


def test_report_separates_code_switching_and_measures_critical_fields():
    report = asyncio.run(evaluate(DATASET))
    assert report['production_validated'] is False
    assert 'sw-KE/code-switch' in report['groups']
    assert sum(group['cases'] for group in report['groups'].values()) == 4
    for group in report['groups'].values():
        assert group['precision'] == group['recall'] == 1
        assert group['critical_field_error_rate'] == 0


def test_wrong_expected_value_fails_cli_and_reports_false_positive_and_negative(tmp_path):
    sample = json.loads(DATASET.read_text())[0]
    sample['expected']['hospital_name'] = 'A different synthetic hospital'
    dataset = tmp_path / 'wrong.json'
    dataset.write_text(json.dumps([sample]))
    result = subprocess.run([sys.executable, '-m', 'app.evaluation', str(dataset), '--check'], capture_output=True, text=True)
    assert result.returncode == 1
    group = json.loads(result.stdout)['groups']['en-KE/text']
    assert group['false_positive'] == group['false_negative'] == 1
    assert group['precision'] == group['recall'] == .75
    assert group['critical_field_error_rate'] == .25


def test_empty_dataset_cannot_pass_a_regression_gate(tmp_path):
    dataset = tmp_path / 'empty.json'
    dataset.write_text('[]')
    with pytest.raises(ValueError, match='non-empty'):
        asyncio.run(evaluate(dataset))
