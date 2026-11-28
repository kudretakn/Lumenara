#!/usr/bin/env python3
"""Fail the required device check unless every WP10 build/device dependency passed."""
import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / "test-results"))
from Wp01Shards import verify_shards


REQUIRED = {"build", "optimized-build", "debug-device", "qa-smoke"}


def verify(needs):
    if not isinstance(needs, dict) or set(needs) != REQUIRED:
        raise ValueError("device must depend on exactly: " + ", ".join(sorted(REQUIRED)))
    failed = [name for name, job in needs.items()
              if not isinstance(job, dict) or job.get("result") != "success"]
    if failed:
        raise ValueError("Required builds/device matrix failed, skipped or cancelled: " + ", ".join(sorted(failed)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--shard-reports", type=Path, default=Path("device-shards"))
    parser.add_argument("--num-shards", type=int, default=3)
    args = parser.parse_args()
    try:
        verify(json.loads(os.environ["WP10_NEEDS"]))
        reports = [json.loads(path.read_text(encoding="utf-8"))
                   for path in sorted(args.shard_reports.rglob("Wp01-results.json"))]
        tests = verify_shards(reports, args.num_shards)
    except (KeyError, ValueError, OSError) as error:
        print("WP10 device gate rejected: " + str(error), file=sys.stderr)
        return 1
    print("All builds, API28/2 GiB debug + QA and API36 debug + QA passed; "
          + str(tests) + " tests covered across " + str(args.num_shards) + " debug shards")
    return 0


if __name__ == "__main__":
    sys.exit(main())
