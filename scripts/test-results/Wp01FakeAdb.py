#!/usr/bin/env python3
"""WP01 fake adb used only by launcher contract tests (never contacts a device)."""
import json
import os
from pathlib import Path
import sys
import time

fixture = json.loads(Path(os.environ["WP01_FIXTURE_FILE"]).read_text())[os.environ["WP01_FIXTURE_NAME"]]
args = sys.argv[1:]
state = Path(os.environ["WP01_FAKE_STATE"])
with state.open("a") as calls:
    calls.write(json.dumps(args) + "\n")
if args == ["devices"]:
    print("List of devices attached\nemulator-5580\tdevice")
elif "instrument" in args:
    if "log" in args:
        attempt = fixture["inventory"]
    elif "per_test" in fixture:
        test = args[args.index("class") + 1]
        calls = [json.loads(line) for line in state.read_text().splitlines()]
        index = sum("instrument" in call and "log" not in call and test in call for call in calls) - 1
        attempts = fixture["per_test"][test]
        attempt = attempts[min(index, len(attempts) - 1)]
    else:
        calls = [json.loads(line) for line in state.read_text().splitlines()]
        index = sum("instrument" in call and "log" not in call for call in calls) - 1
        attempt = fixture["attempts"][min(index, len(fixture["attempts"]) - 1)]
    print(attempt.get("prefix", ""), end="", flush=True)
    time.sleep(attempt.get("sleep", 0))
    print(attempt["text"], end="", flush=True)
    sys.exit(attempt["exit_code"])
elif (fixture.get("artifact_failure")
      and fixture["artifact_failure"].get("command", "logcat") in args
      and ("pull" in args or "-d" in args)):
    failure = fixture["artifact_failure"]
    time.sleep(failure.get("sleep", 0))
    print(failure.get("text", ""), flush=True)
    sys.exit(failure["exit_code"])
elif os.environ.get("WP01_HANG_ARTIFACTS") and ("pull" in args or ("logcat" in args and "-d" in args)):
    time.sleep(5)
