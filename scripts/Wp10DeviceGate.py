#!/usr/bin/env python3
"""Fail the required device check unless every WP10 build/device dependency passed."""
import json
import os
import sys


REQUIRED = {"build", "optimized-build", "debug-device", "qa-smoke"}


def verify(needs):
    if not isinstance(needs, dict) or set(needs) != REQUIRED:
        raise ValueError("device must depend on exactly: " + ", ".join(sorted(REQUIRED)))
    failed = [name for name, job in needs.items()
              if not isinstance(job, dict) or job.get("result") != "success"]
    if failed:
        raise ValueError("Required builds/device matrix failed, skipped or cancelled: " + ", ".join(sorted(failed)))


def main():
    try:
        verify(json.loads(os.environ["WP10_NEEDS"]))
    except (KeyError, ValueError) as error:
        print("WP10 device gate rejected: " + str(error), file=sys.stderr)
        return 1
    print("All builds, API28/2 GiB debug + QA and API36 debug + QA passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
