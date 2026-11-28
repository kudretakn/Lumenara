"""Exercise real debug launcher isolation, retries and cross-shard inventory coverage."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from Wp01Shards import partition_tests, verify_shards

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent


def protocol(tests, status=0):
    text = ""
    for test in tests:
        cls, method = test.split("#")
        bundle = (f"INSTRUMENTATION_STATUS: class={cls}\n"
                  f"INSTRUMENTATION_STATUS: test={method}\n"
                  f"INSTRUMENTATION_STATUS: numtests={len(tests)}\n")
        text += bundle + "INSTRUMENTATION_STATUS_CODE: 1\n"
        text += bundle + f"INSTRUMENTATION_STATUS_CODE: {status}\n"
    text += (f"OK ({len(tests)} tests)\n" if status == 0 else "FAILURES!!!\n")
    return {"text": text + "INSTRUMENTATION_CODE: -1\n", "exit_code": 0}


class Wp01ShardsTest(unittest.TestCase):
    def launch(self, tests, overrides=None, count=1, index=0, timeout=2, total=60, reserve=5):
        fixture = {"inventory": protocol(tests),
                   "per_test": {test: [protocol([test])] for test in tests}}
        fixture["per_test"].update(overrides or {})
        with tempfile.TemporaryDirectory(prefix="Wp01 shard ") as directory:
            temp = Path(directory)
            fixture_file = temp / "fixtures.json"
            fixture_file.write_text(json.dumps({"sharded": fixture}))
            adb = temp / "fake adb"
            adb.write_text("#!" + sys.executable + "\n" + (HERE / "Wp01FakeAdb.py").read_text())
            adb.chmod(0o755)
            state, out = temp / "calls.jsonl", temp / "screens"
            env = {**os.environ, "WP01_FIXTURE_FILE": str(fixture_file),
                   "WP01_FIXTURE_NAME": "sharded", "WP01_FAKE_STATE": str(state),
                   "WP01_PYTHON": sys.executable, "PYTHONDONTWRITEBYTECODE": "1"}
            result = subprocess.run(["bash", str(ROOT / "scripts/ci-device-tests.sh"), ".", str(out),
                                     "--adb", str(adb), "--serial", "emulator-5580",
                                     "--num-shards", str(count), "--shard-index", str(index),
                                     "--test-timeout", str(timeout), "--total-timeout", str(total),
                                     "--artifact-reserve", str(reserve)],
                                    env=env, capture_output=True, text=True, timeout=90)
            report = json.loads((out / "Wp01-results.json").read_text())
            self.assertEqual(report["success"], result.returncode == 0, result.stdout + result.stderr)
            self.assertEqual(sorted(tests), (out / "all-tests.txt").read_text().splitlines())
            self.assertEqual(partition_tests(tests, count)[index],
                             (out / "shard-tests.txt").read_text().splitlines())
            calls = [json.loads(line) for line in state.read_text().splitlines()]
            return report, calls, (out / "summary.txt").read_text()

    def test_every_test_runs_once_across_three_real_launcher_shards(self):
        tests = [f"dev.lumenara.tv.Class{i}#method{j}" for i in range(6) for j in range(i % 3 + 1)]
        reports = []
        for index in range(3):
            report, calls, _ = self.launch(tests, count=3, index=index)
            self.assertTrue(report["success"], report["errors"])
            reports.append(report)
            executed = [call[call.index("class") + 1] for call in calls
                        if "instrument" in call and "log" not in call]
            self.assertEqual(partition_tests(tests, 3)[index], executed)
            discovery = [call for call in calls if "instrument" in call and "log" in call]
            self.assertEqual(1, len(discovery))
            self.assertNotIn("class", discovery[0])
        self.assertEqual(len(tests), verify_shards(reports, 3))

    def test_partition_is_order_independent_balanced_and_keeps_whole_classes(self):
        tests = [f"example.Class{i}#method{j}" for i in range(20) for j in range(i % 5 + 1)]
        for count in (1, 2, 3, 4, 7):
            with self.subTest(count=count):
                shards = partition_tests(tests, count)
                self.assertEqual(shards, partition_tests(reversed(tests), count))
                self.assertEqual(sorted(tests), sorted(test for shard in shards for test in shard))
                owners = {}
                for index, shard in enumerate(shards):
                    for test in shard:
                        cls = test.split("#")[0]
                        self.assertEqual(index, owners.setdefault(cls, index))
                self.assertLessEqual(max(map(len, shards)) - min(map(len, shards)), 5)

    def test_timeout_is_red_after_clean_retry_and_next_test_still_runs(self):
        tests = ["example.A#hang", "example.B#pass"]
        hang = {"prefix": "INSTRUMENTATION_STATUS: class=example.A\n"
                "INSTRUMENTATION_STATUS: test=hang\nINSTRUMENTATION_STATUS_CODE: 1\n",
                "text": "", "exit_code": 0, "sleep": 60}
        report, calls, summary = self.launch(tests, {tests[0]: [hang, protocol([tests[0]])]})
        self.assertFalse(report["success"])
        self.assertEqual({tests[0]: "TIMEOUT", tests[1]: "PASS"}, report["results"])
        self.assertIn("TIMEOUT  " + tests[0], summary)
        self.assertEqual(["TIMEOUT", "PASS", "PASS"],
                         [next(iter(a["results"].values())) for a in report["attempts"]])
        self.assertEqual([124, 0, 0], [c["exit_code"] for c in report["commands"]
                                     if c["name"] in ("instrument-test-1", "instrument-test-2", "instrument-retry-1")])
        instruments = [i for i, call in enumerate(calls) if "instrument" in call and "log" not in call]
        stops = [i for i, call in enumerate(calls) if "force-stop" in call]
        self.assertEqual(2, len(stops))  # App and instrumentation package, before the next test.
        self.assertTrue(all(instruments[0] < stop < instruments[1] for stop in stops))
        self.assertLess(report["elapsed_seconds"], 60)

    def test_failed_test_retry_stays_visible(self):
        test = "example.A#failsOnce"
        report, _, summary = self.launch([test], {test: [protocol([test], -2), protocol([test])]})
        self.assertTrue(report["success"], report["errors"])
        self.assertEqual({test: "RETRIED"}, report["results"])
        self.assertEqual(2, len(report["attempts"]))
        self.assertIn("RETRIED  " + test, summary)

    def test_total_budget_is_shared_in_test_isolation_mode(self):
        tests = ["example.A#hang", "example.B#pass"]
        report, _, _ = self.launch(tests, {tests[0]: [{"text": "", "exit_code": 0, "sleep": 60}]},
                                   timeout=10, total=4, reserve=1)
        self.assertFalse(report["success"])
        self.assertEqual(1, len(report["attempts"]))
        self.assertEqual({tests[0]: "TIMEOUT"}, report["results"])
        self.assertIn("Total time budget exhausted before all tests ran", report["errors"])
        self.assertIn("Total time budget exhausted before retries", report["errors"])
        self.assertTrue(any(error.startswith("Tests never ran:") for error in report["errors"]))
        self.assertTrue(any(command["name"] == "logcat" for command in report["commands"]))
        self.assertLess(report["elapsed_seconds"], 4.5)

    def test_crash_does_not_stop_later_tests_or_become_green(self):
        tests = ["example.A#crash", "example.B#pass"]
        report, calls, _ = self.launch(tests, {tests[0]: [{"text": "INSTRUMENTATION_FAILED: crash\n",
                                                        "exit_code": 1}]})
        self.assertFalse(report["success"])
        self.assertEqual("PASS", report["results"][tests[1]])
        self.assertTrue(any("Tests never ran" in error for error in report["errors"]))
        self.assertTrue(any("force-stop" in call for call in calls))

    def test_invalid_shard_timeout_or_stateful_combination_fails_before_adb(self):
        for options in (["--num-shards", "0"], ["--num-shards", "3", "--shard-index", "3"],
                        ["--shard-index", "-1"], ["--test-timeout", "0"], ["--test-timeout", "nan"],
                        ["--single-run", "--test-timeout", "180"], ["--single-run", "--num-shards", "3"]):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory:
                result = subprocess.run([sys.executable, "-B", str(HERE / "Wp01DeviceTests.py"),
                                         ".", directory, "--adb", "/does-not-exist"] + options,
                                        capture_output=True, text=True, timeout=20)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(json.loads((Path(directory) / "Wp01-results.json").read_text())["success"])


if __name__ == "__main__":
    unittest.main()
