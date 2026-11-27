#!/usr/bin/env python3
"""WP01 release provenance and the unchanged personal-certificate pin."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

EXPECTED_CERT_SHA256 = "2a4804f05adc3206055fd91777c6cc7c370ee1b3a23c9822eda3156aac9a80eb"


class ReleaseError(Exception):
    pass


def check_certificate(text):
    # Require one signer, not just a matching first certificate among several.
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$", text, re.M)
    if len(digests) != 1 or digests[0].lower() != EXPECTED_CERT_SHA256:
        raise ReleaseError("APK must have exactly the original personal release certificate")


def select_run(runs, sha, repository):
    matching = [run for run in runs if run.get("head_sha") == sha
                and run.get("head_branch") == "main" and run.get("event") == "push"
                and run.get("head_repository", {}).get("full_name") == repository]
    if not matching:
        raise ReleaseError("No Android CI main push run exists for the exact release SHA")
    # An old green run cannot hide a newer pending, cancelled or failed run.
    return max(matching, key=lambda run: (run["run_number"], run["id"]))


def check_run(run, jobs):
    if run.get("status") != "completed" or run.get("conclusion") != "success":
        raise ReleaseError("Latest Android CI run for this SHA is not completed and successful")
    names = {job.get("name") for job in jobs}
    if not {"build", "device"}.issubset(names):
        raise ReleaseError("Android CI must include successful build and aggregate device jobs")
    if any(job.get("status") != "completed" or job.get("conclusion") != "success" for job in jobs):
        raise ReleaseError("Every Android CI job must finish successfully (no skipped jobs)")


def output(argv):
    return subprocess.check_output(argv, text=True, timeout=60).strip()


def api(path):
    return json.loads(output(["gh", "api", path]))


def pages(path, key):
    found, page = [], 1
    while True:
        value = api(path + ("&" if "?" in path else "?") + "per_page=100&page=" + str(page))
        found.extend(value[key])
        if len(value[key]) < 100:
            return found
        page += 1


def check_ci(sha, repository):
    sha = output(["git", "rev-parse", sha + "^{commit}"])
    if output(["git", "rev-parse", "HEAD"]) != sha:
        raise ReleaseError("Checkout does not match the release SHA")
    if subprocess.run(["git", "merge-base", "--is-ancestor", sha, "refs/remotes/origin/main"],
                      timeout=60, check=False).returncode != 0:
        raise ReleaseError("Release SHA is not on origin/main")
    prefix = "repos/" + repository + "/actions"
    runs = pages(prefix + "/workflows/android.yml/runs?branch=main&event=push&head_sha=" + sha, "workflow_runs")
    run = select_run(runs, sha, repository)
    jobs = pages(prefix + "/runs/" + str(run["id"]) + "/jobs?filter=latest", "jobs")
    check_run(run, jobs)
    # Reject reruns started while jobs were read; never pair different attempts.
    current = api(prefix + "/runs/" + str(run["id"]))
    if current.get("run_attempt") != run.get("run_attempt"):
        raise ReleaseError("Android CI attempt changed while checking the release gate")
    check_run(current, jobs)
    print("Verified main SHA " + sha + " against Android CI run " + str(run["id"]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    ci = sub.add_parser("ci")
    ci.add_argument("--sha", required=True)
    ci.add_argument("--repository", required=True)
    cert = sub.add_parser("certificate")
    cert.add_argument("verification_output")
    args = parser.parse_args()
    try:
        if args.command == "ci":
            check_ci(args.sha, args.repository)
        else:
            check_certificate(Path(args.verification_output).read_text(encoding="utf-8-sig"))
            print("Verified original personal release certificate")
        return 0
    except (ReleaseError, OSError, ValueError, subprocess.SubprocessError) as error:
        print("Release gate rejected: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
