#!/usr/bin/env python3
"""WP01: one instrumentation protocol, deadline and verdict for both launchers."""
import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time


class GateError(Exception):
    pass


def parse_instrumentation(text, exit_code):
    """Parse status bundles, never reusing a previous test's identity."""
    results, errors, totals = {}, [], set()
    bundle = {}
    end_codes = []
    normal_end = False
    failure_summary = False
    summaries, ignored = [], 0
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if end_codes:
                errors.append("Test status after final completion")
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if sep:
                bundle[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                code = int(line.split(": ", 1)[1])
            except ValueError:
                errors.append("Malformed status code")
                bundle = {}
                continue
            if "numtests" in bundle:
                try:
                    totals.add(int(bundle["numtests"]))
                except ValueError:
                    errors.append("Malformed numtests")
            if code in (0, -1, -2, -3, -4):
                cls, method = bundle.get("class", ""), bundle.get("test", "")
                if not cls or not method or re.search(r"[\s,#]", cls + method):
                    errors.append("Terminal result without a valid class/test")
                else:
                    test = cls + "#" + method
                    if test in results:
                        errors.append("Duplicate terminal result: " + test)
                    ignored += int(code == -3)
                    results[test] = {0: "PASS", -1: "FAIL", -2: "FAIL", -3: "SKIP", -4: "SKIP"}[code]
            elif code not in (1, 2):
                errors.append("Unknown runner status: " + str(code))
            if "Error" in bundle or "shortMsg" in bundle:
                errors.append("Runner reported an error")
            bundle = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            end_codes.append(line.split(": ", 1)[1])
        if re.match(r"^(OK \(\d+ tests?\)|FAILURES!!!)\s*$", line):
            normal_end = True
            summaries.append(line.strip())
            failure_summary = line.strip() == "FAILURES!!!"
        if re.match(r"^INSTRUMENTATION_(FAILED|ABORTED):", line) or re.match(
            r"^INSTRUMENTATION_RESULT: (shortMsg|Error)=", line
        ):
            errors.append("Instrumentation failed or aborted")
    if exit_code != 0:
        errors.append("Instrumentation exit: " + str(exit_code))
    if end_codes != ["-1"] or not normal_end or len(summaries) != 1:
        errors.append("Missing or invalid normal instrumentation completion")
    if len(summaries) == 1 and not failure_summary:
        count = int(re.search(r"\d+", summaries[0]).group())
        if count != len(results) - ignored or "FAIL" in results.values():
            errors.append("JUnit summary differs from terminal results")
    if failure_summary and "FAIL" not in results.values():
        errors.append("Runner failure summary without a failed test")
    if not results:
        errors.append("Zero terminal results")
    if len(totals) != 1 or next(iter(totals), 0) != len(results):
        errors.append("Missing/inconsistent test inventory (numtests)")
    return {"results": results, "errors": errors}


def final_verdict(inventory, attempts, allowed_skips):
    errors = list(inventory["errors"])
    expected = set(inventory["results"])
    if any(v == "FAIL" for v in inventory["results"].values()):
        errors.append("Inventory discovery failed")
    final = {}
    for attempt in attempts:
        errors.extend(attempt["errors"])
        actual = attempt["results"]
        requested = set(attempt.get("expected", expected))
        if set(actual) != requested:
            errors.append("Attempt results differ from requested inventory")
        for test, status in actual.items():
            if test not in expected:
                errors.append("Unexpected test: " + test)
                continue
            old = final.get(test)
            if old is not None and old != "FAIL":
                errors.append("Unexpected repeat: " + test)
            if old == "FAIL":
                final[test] = "RETRIED" if status == "PASS" else "FAIL"
            else:
                final[test] = status
    if set(final) != expected:
        errors.append("Tests never ran: " + ", ".join(sorted(expected - set(final))))
    for test, status in final.items():
        if status == "FAIL":
            errors.append("Failed test: " + test)
        elif status == "SKIP" and test not in allowed_skips:
            errors.append("Unexpected skip: " + test)
    if not any(status in ("PASS", "RETRIED") for status in final.values()):
        errors.append("No passing tests")
    return final, sorted(set(errors))


class Deadline:
    def __init__(self, total, reserve):
        if not math.isfinite(total) or not math.isfinite(reserve) or total <= reserve or reserve < 1:
            raise GateError("Total timeout must exceed the positive artifact reserve")
        self.started = time.monotonic()
        self.ends = self.started + total
        self.reserve = reserve

    def remaining(self, artifacts=False):
        return max(0, self.ends - time.monotonic() - (0 if artifacts else self.reserve))

    def run(self, argv, log, cap, artifacts=False):
        remaining = self.remaining(artifacts)
        if remaining <= 0:
            Path(log).write_text("WP01: total time budget exhausted\n", encoding="utf-8")
            return 124
        with Path(log).open("w", encoding="utf-8") as output:
            try:
                return subprocess.run(argv, stdout=output, stderr=subprocess.STDOUT,
                                      timeout=min(cap, remaining), check=False).returncode
            except subprocess.TimeoutExpired:
                output.write("\nWP01: command timed out\n")
                return 124
            except OSError as error:
                output.write("WP01: command could not start: " + str(error) + "\n")
                return 127


def run_device(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    # A summary exists even for setup failures: android.yml must not retry infrastructure
    # failures as though the emulator had never started. Old logs cannot enter the verdict.
    (out / "summary.txt").write_text("INFRA  Runner starting\n", encoding="utf-8")
    deadline = Deadline(args.total_timeout, args.artifact_reserve)
    inventory = {"results": {}, "errors": ["No test inventory"]}
    attempts, setup_errors, commands = [], [], []
    allowed = json.loads(Path(args.allowed_skips).read_text(encoding="utf-8"))
    if not isinstance(allowed, dict) or any(not isinstance(v, str) or not v.strip() for v in allowed.values()):
        raise GateError("Allowed skips must map exact Class#method IDs to nonempty reasons")
    adb = [args.adb]
    if args.serial:
        serial = args.serial
    else:
        devices_log = out / "devices.txt"
        if deadline.run(adb + ["devices"], devices_log, 15) != 0:
            raise GateError("Cannot list emulator devices")
        devices = re.findall(r"^(emulator-\d+)\s+device\s*$", devices_log.read_text(), re.M)
        if len(devices) != 1:
            raise GateError("Pass --serial (or ANDROID_SERIAL) for exactly one emulator")
        serial = devices[0]
    if not re.fullmatch(r"emulator-\d+", serial):
        raise GateError("Refusing to run tests on a non-emulator device: " + serial)
    adb += ["-s", serial]
    package = args.package
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*", package):
        raise GateError("Invalid app package")
    runner = args.runner
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.]*/[A-Za-z][A-Za-z0-9_.]*", runner):
        raise GateError("Invalid instrumentation runner")
    if args.test_class and not re.fullmatch(r"[A-Za-z0-9_.$#,\[\]-]+", args.test_class):
        raise GateError("Invalid class filter")
    app_apk = args.apk or str(Path(args.apk_dir) / "app-debug.apk")
    test_apk = args.test_apk or str(Path(args.apk_dir) / "app-debug-androidTest.apk")

    def command(name, argv, cap, artifacts=False):
        log = out / (name + ".txt")
        start = time.monotonic()
        code = deadline.run(adb + argv, log, cap, artifacts)
        commands.append({"name": name, "argv": adb + argv, "exit_code": code,
                         "seconds": round(time.monotonic() - start, 3)})
        return log, code

    def instrument(name, extra):
        log, code = command(name, ["shell", "am", "instrument", "-w", "-r"] + extra + [runner],
                            180 if name == "test-list" else 1680)
        parsed = parse_instrumentation(log.read_text(encoding="utf-8", errors="replace"), code)
        parsed["log"] = log.name
        return parsed

    try:
        for name, argv in [
            ("logcat-clear", ["logcat", "-c"]),
            ("animation-window", ["shell", "settings", "put", "global", "window_animation_scale", "0"]),
            ("animation-transition", ["shell", "settings", "put", "global", "transition_animation_scale", "0"]),
            ("animation-animator", ["shell", "settings", "put", "global", "animator_duration_scale", "0"]),
            ("install-app", ["install", "-r", "-g", app_apk]),
            ("install-tests", ["install", "-r", "-t", "-g", test_apk]),
        ]:
            if command(name, argv, 120)[1] != 0:
                raise GateError("Setup failed: " + name)
        selected = ["-e", "class", args.test_class] if args.test_class else []
        inventory = instrument("test-list", ["-e", "log", "true"] + selected)
        (out / "all-tests.txt").write_text("\n".join(sorted(inventory["results"])) + "\n", encoding="utf-8")
        if inventory["errors"] or "FAIL" in inventory["results"].values():
            raise GateError("Inventory discovery did not complete successfully")
        first = instrument("instrument", selected)
        first["expected"] = sorted(inventory["results"])
        attempts.append(first)
        # Stateful QA uses a single execution, like connectedQaAndroidTest. Starting
        # instrumentation again force-stops the app but retains its WorkManager DB.
        # Default debug policy still continues missing tests and retries failures.
        for part in ([] if args.single_run else range(1, 4)):
            done = set().union(*(set(a["results"]) for a in attempts))
            missing = sorted(set(inventory["results"]) - done)
            if not missing or deadline.remaining() <= 0:
                break
            continued = instrument("instrument-continue-" + str(part), ["-e", "class", ",".join(missing)])
            continued["expected"] = missing
            attempts.append(continued)
        failed = sorted({test for a in attempts for test, status in a["results"].items() if status == "FAIL"})
        for index, test in enumerate([] if args.single_run else failed, 1):
            if deadline.remaining() <= 0:
                setup_errors.append("Total time budget exhausted before retries")
                break
            retry = instrument("instrument-retry-" + str(index), ["-e", "class", test])
            retry["expected"] = [test]
            attempts.append(retry)
    except GateError as error:
        setup_errors.append(str(error))
    finally:
        # Collection shares the final reserve. Only absent optional directories
        # are expected; disconnects, logcat errors and collection timeouts stay red.
        for name, argv in [
            ("logcat", ["logcat", "-d", "-v", "time"]),
            ("pull-files", ["pull", "/sdcard/Android/data/" + package + "/files/", str(out)]),
            ("pull-screens", ["pull", "/sdcard/Download/lumenara-ui", str(out)]),
        ]:
            log, code = command(name, argv, args.artifact_reserve / 3, artifacts=True)
            missing_optional_directory = name.startswith("pull-") and code == 1 and re.search(
                r"No such file or directory|remote object .* does not exist",
                log.read_text(encoding="utf-8", errors="replace")
            )
            if code != 0 and not missing_optional_directory:
                setup_errors.append("Artifact collection failed: " + name + " (exit " + str(code) + ")")
    final, errors = final_verdict(inventory, attempts, allowed)
    errors = sorted(set(errors + setup_errors))
    lines = [status + "  " + test + (" (allowed: " + allowed[test] + ")" if status == "SKIP" and test in allowed else "")
             for test, status in sorted(final.items())]
    lines += [("GATE  " if error.startswith(("Failed test:", "Unexpected skip:", "No passing tests")) else "INFRA  ")
              + error for error in errors]
    (out / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
    report = {"schema_version": 1, "success": not errors, "inventory": inventory,
              "attempts": attempts, "results": final, "errors": errors, "allowed_skips": allowed,
              "commands": commands, "elapsed_seconds": round(time.monotonic() - deadline.started, 3),
              "single_run": args.single_run,
              "total_timeout_seconds": args.total_timeout, "artifact_reserve_seconds": args.artifact_reserve}
    (out / "Wp01-results.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("\n".join(lines))
    return int(bool(errors))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk_dir", nargs="?", default="apks")
    parser.add_argument("out", nargs="?", default="screens")
    parser.add_argument("--apk")
    parser.add_argument("--test-apk")
    parser.add_argument("--package", default="dev.lumenara.tv.debug")
    parser.add_argument("--runner", default="dev.lumenara.tv.debug.test/androidx.test.runner.AndroidJUnitRunner")
    parser.add_argument("--class", dest="test_class", default="")
    parser.add_argument("--single-run", action="store_true",
                        help="Execute inventory once; no continuations or retries against persisted state")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--total-timeout", type=float, default=1800)
    parser.add_argument("--artifact-reserve", type=float, default=120)
    parser.add_argument("--allowed-skips", default=str(Path(__file__).with_name("Wp01AllowedSkips.json")))
    args = parser.parse_args()
    try:
        return run_device(args)
    except (GateError, OSError, ValueError) as error:
        message = "INFRA  " + str(error)
        print(message, file=sys.stderr)
        out = Path(args.out)
        out.mkdir(parents=True, exist_ok=True)
        (out / "summary.txt").write_text(message + "\n", encoding="utf-8")
        (out / "Wp01-results.json").write_text(json.dumps({"schema_version": 1, "success": False,
                                                         "errors": [str(error)]}) + "\n", encoding="utf-8")
        return 1


if __name__ == "__main__":
    sys.exit(main())
