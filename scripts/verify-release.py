#!/usr/bin/env python3
"""Independently inspect an existing candidate and optionally save evidence outside it."""
import argparse
import json
from pathlib import Path
from release_tools import ROOT, VERSION, verify_release

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=Path, default=ROOT / 'release' / VERSION)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        report = verify_release(ROOT, args.directory)
        if args.report:
            candidate = args.directory.resolve()
            if args.report.resolve().is_relative_to(candidate):
                raise ValueError('Verification reports must be saved outside the immutable candidate')
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report, indent=2))
    except (ValueError, OSError) as error:
        raise SystemExit(str(error)) from None
