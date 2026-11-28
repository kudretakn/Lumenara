"""Time unchanged CI task scopes; optionally compare two uncached and two cached clean builds."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
SCOPES = {
    "debug": ["test", "lintDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest"],
    "optimized": ["-Pwp02.testBuildType=qa", ":app:assembleQa", ":app:assembleQaAndroidTest", ":app:assembleRelease"],
}


def input_hashes(root):
    paths = [root / "settings.gradle.kts", root / "gradle.properties", root / "gradle/libs.versions.toml",
             root / "gradle/verification-metadata.xml", root / "gradle/wrapper/gradle-wrapper.properties",
             root / "gradle/wrapper/gradle-wrapper.jar", root / "build.gradle.kts", root / "app/build.gradle.kts",
             root / "baselineprofile/build.gradle.kts", root / "gradle/Wp20BuildEvidence.py"]
    paths += list((root / "gradle/wp20-locks").glob("*.lockfile"))
    paths += list((root / "core").glob("*/build.gradle.kts"))
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(paths) if path.is_file()}


def task_counts(log):
    states = {"FROM-CACHE": 0, "UP-TO-DATE": 0, "NO-SOURCE": 0, "SKIPPED": 0, "executed": 0}
    for line in log.splitlines():
        if re.match(r"^> Task :", line):
            state = line.rsplit(" ", 1)[-1]
            states[state if state in states else "executed"] += 1
    return states


def run_sample(command, label, directory, root, run=subprocess.run, timer=time.monotonic):
    log_path = directory / (label + ".log")
    started = timer()
    with log_path.open("w") as log:
        try:
            result = run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, text=True, timeout=3600)
            code = result.returncode
        except subprocess.TimeoutExpired:
            code = 124
    elapsed = timer() - started
    content = log_path.read_text(errors="replace")
    sample = {"label": label, "command": command, "seconds": round(elapsed, 3), "exit_code": code,
              "log": log_path.name, "tasks": task_counts(content),
              "configuration_cache_reused": "Reusing configuration cache." in content,
              "log_sha256": hashlib.sha256(log_path.read_bytes()).hexdigest()}
    print(f"WP20 {label}: {elapsed:.3f}s, exit={code}, tasks={sample['tasks']}", flush=True)
    if code:
        # A stack trace can push the exception and missing-artifact list out of
        # a short tail. Keep the complete failure visible in the CI job log.
        print(content, file=sys.stderr)
    return sample


def measure(scope, directory, compare=False, root=ROOT, run=subprocess.run, timer=time.monotonic):
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    wrapper = str(root / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    base = [wrapper, "--stacktrace", "--console=plain", "--dependency-verification=strict"]
    report = {"scope": scope, "tasks": SCOPES[scope], "compare": compare, "samples": [],
              "utc": datetime.now(timezone.utc).isoformat(), "host": platform.platform(),
              "sha": os.environ.get("GITHUB_SHA"), "input_sha256": input_hashes(root),
              "conditions": "Same tasks and dependencies. Clean time excluded from build times. "
                            "Dependency downloads, OS caches and Gradle daemon remain warm/shared; "
                            "this is not a cold-machine benchmark. No device tests are removed."}
    try:
        with tempfile.TemporaryDirectory(prefix="wp20-build-caches-") as temp:
            modes = [("off-1", False), ("off-2", False), ("on-1", True), ("on-2", True)] if compare else [("ordinary", None)]
            for label, enabled in modes:
                shared = (["--project-cache-dir", str(Path(temp) / "project"),
                           "-Pwp20.buildCacheDir=" + str(Path(temp) / "tasks")] if compare else [])
                if compare:
                    clean = run_sample(base + shared + ["--no-build-cache", "--no-configuration-cache", "clean"],
                                       label + "-clean", directory, root, run, timer)
                    report["samples"].append(clean)
                    if clean["exit_code"]:
                        return clean["exit_code"]
                flags = [] if enabled is None else (["--build-cache", "--configuration-cache"] if enabled else
                                                   ["--no-build-cache", "--no-configuration-cache"])
                sample = run_sample(base + shared + flags + SCOPES[scope], label, directory, root, run, timer)
                report["samples"].append(sample)
                if sample["exit_code"]:
                    return sample["exit_code"]
                if label == "on-2" and not sample["configuration_cache_reused"]:
                    report["error"] = "The second cached build did not reuse configuration cache; investigate before acceptance."
                    return 1
        return 0
    finally:
        (directory / "timings.json").write_text(json.dumps(report, indent=2) + "\n")
        rows = ["# WP20 build timings", "", report["conditions"], "",
                "| Run | Seconds | Exit | From cache | Config reused |", "|---|---:|---:|---:|---|"]
        rows += [f"| {s['label']} | {s['seconds']} | {s['exit_code']} | {s['tasks']['FROM-CACHE']} | {s['configuration_cache_reused']} |"
                 for s in report["samples"]]
        if "error" in report:
            rows += ["", report["error"]]
        (directory / "timings.md").write_text("\n".join(rows) + "\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=SCOPES, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compare", action="store_true")
    args = parser.parse_args(argv)
    return measure(args.scope, args.output, args.compare)


if __name__ == "__main__":
    sys.exit(main())
