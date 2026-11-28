"""WP10 aggregate verdict and the actual prebuilt QA launcher, with fake adb only."""
import importlib.util
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import re


ROOT = Path(__file__).resolve().parents[4]
SPEC = importlib.util.spec_from_file_location("wp10_gate", ROOT / "scripts/Wp10DeviceGate.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class Wp10DeviceGateTest(unittest.TestCase):
    def shard_reports(self):
        tests = [f"example.Class{i}#test{j}" for i in range(6) for j in range(i % 3 + 1)]
        full = {test: "PASS" for test in tests}
        from Wp01Shards import ALGORITHM, partition_tests
        return [{"schema_version": 1, "success": True, "errors": [],
                 "shard": {"count": 3, "index": i, "algorithm": ALGORITHM},
                 "full_inventory": {"results": full.copy(), "errors": []},
                 "inventory": {"results": {test: "PASS" for test in shard}, "errors": []},
                 "results": {test: "PASS" for test in shard}}
                for i, shard in enumerate(partition_tests(tests, 3))]

    def test_every_required_job_must_pass_and_no_dependency_may_disappear(self):
        good = {name: {"result": "success"} for name in GATE.REQUIRED}
        GATE.verify(good)
        for name in GATE.REQUIRED:
            for result in ("failure", "skipped", "cancelled", None):
                with self.subTest(name=name, result=result), self.assertRaises(ValueError):
                    GATE.verify({**good, name: {"result": result}})
            with self.subTest(missing=name), self.assertRaises(ValueError):
                GATE.verify({key: value for key, value in good.items() if key != name})
        for invalid in (None, [], {}, {**good, "unreviewed-matrix": {"result": "success"}}):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                GATE.verify(invalid)

    def test_actual_gate_entrypoint_fails_closed(self):
        good = {name: {"result": "success"} for name in GATE.REQUIRED}
        for needs, count, success in ((good, 3, True), ({}, 3, False), (good, 2, False), (good, 0, False)):
            with self.subTest(count=count, needs=needs), tempfile.TemporaryDirectory() as directory:
                for report in self.shard_reports()[:count]:
                    target = Path(directory) / str(report["shard"]["index"])
                    target.mkdir()
                    (target / "Wp01-results.json").write_text(json.dumps(report))
                result = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/Wp10DeviceGate.py"),
                                         "--shard-reports", directory, "--num-shards", "3"],
                                        env={**os.environ, "WP10_NEEDS": json.dumps(needs)}, capture_output=True)
                self.assertEqual(success, result.returncode == 0, result.stderr)

    def test_union_gate_rejects_missing_duplicate_divergent_and_failed_shards(self):
        reports = self.shard_reports()
        self.assertEqual(12, GATE.verify_shards(reports, 3))
        cases = [reports[:2], reports + [reports[0]], [reports[0], reports[0], reports[2]]]
        for fault in ("different-full", "drop-test", "overlap", "wrong-owner", "failure", "timeout",
                      "inventory-error", "full-error", "unknown-algorithm", "wrong-count", "never-ran"):
            changed = copy.deepcopy(reports)
            report = changed[0]
            test = next(iter(report["results"]))
            other = next(iter(changed[1]["results"]))
            if fault == "different-full":
                del report["full_inventory"]["results"][test]
            elif fault == "drop-test":
                del report["inventory"]["results"][test]
                del report["results"][test]
            elif fault == "overlap":
                report["inventory"]["results"][other] = "PASS"
                report["results"][other] = "PASS"
            elif fault == "wrong-owner":
                for key in ("inventory", "results"):
                    report[key], changed[1][key] = changed[1][key], report[key]
            elif fault == "failure":
                report["success"] = False
            elif fault == "timeout":
                report["results"][test] = "TIMEOUT"
            elif fault == "inventory-error":
                report["inventory"]["errors"] = ["crash"]
            elif fault == "full-error":
                report["full_inventory"]["errors"] = ["discovery crash"]
            elif fault == "unknown-algorithm":
                report["shard"]["algorithm"] = "unknown"
            elif fault == "wrong-count":
                report["shard"]["count"] = 4
            elif fault == "never-ran":
                del report["results"][test]
            cases.append(changed)
        for invalid in cases:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                GATE.verify_shards(invalid, 3)

    def test_union_gate_accepts_visible_retries_and_only_reviewed_skips(self):
        reports = self.shard_reports()
        tests = list(reports[0]["results"])
        reports[0]["results"][tests[0]] = "RETRIED"
        reports[0]["results"][tests[1]] = "SKIP"
        with self.assertRaises(ValueError):
            GATE.verify_shards(reports, 3)
        reports[0]["allowed_skips"] = {tests[1]: "Reviewed platform reason"}
        self.assertEqual(12, GATE.verify_shards(reports, 3))

    def test_workflow_runs_every_configured_shard_and_preserves_other_device_modes(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text()
        count = int(re.search(r"DEBUG_API36_SHARDS: '(\d+)'", workflow)[1])
        debug = workflow.split("\n  debug-device:", 1)[1].split("\n  qa-smoke:", 1)[0]
        indices = [int(i) for i in re.findall(r"shard_index: (\d+)", debug)]
        self.assertEqual(list(range(count)), sorted(indices))
        self.assertEqual(count, debug.count("- api: 36"))
        self.assertEqual(1, debug.count("- api: 28"))
        self.assertIn("fail-fast: false", debug)
        self.assertEqual(count, len(set(re.findall(r"artifact_suffix: '(-s\d+)'", debug))))
        scripts = re.findall(r"script: (.+)", debug)
        self.assertEqual(2, len(scripts))
        self.assertEqual(scripts[0], scripts[1])
        self.assertIn("matrix.api == 36", scripts[0])
        self.assertIn("--test-timeout 180", scripts[0])
        self.assertIn("env.DEBUG_API36_SHARDS, matrix.shard_index", scripts[0])
        qa = workflow.split("\n  qa-smoke:", 1)[1].split("\n  device:", 1)[0]
        self.assertNotIn("--test-timeout", qa)
        self.assertEqual(2, qa.count("script: ./scripts/qa-smoke.sh apks screens"))
        aggregate = workflow.split("\n  device:", 1)[1]
        self.assertIn("needs: [build, optimized-build, debug-device, qa-smoke]", aggregate)
        self.assertIn("pattern: android-tv-debug-api36-s*", aggregate)
        self.assertIn("--shard-reports device-shards --num-shards ${{ env.DEBUG_API36_SHARDS }}", aggregate)

    def test_qa_launcher_keeps_strict_gate_but_never_restarts_the_stateful_smoke(self):
        for fixture in ("pass", "pass_then_crash", "missing_finish", "unexpected_skip", "only_retried",
                        "missing_result", "error_status", "artifact_infrastructure_error"):
            with self.subTest(fixture=fixture), tempfile.TemporaryDirectory(prefix="Wp10 QA space ") as directory:
                temp = Path(directory)
                fake_adb = temp / "fake adb"
                fake_adb.write_text("#!" + sys.executable + "\n" +
                                    (ROOT / "scripts/test-results/Wp01FakeAdb.py").read_text().split("\n", 1)[1])
                fake_adb.chmod(0o755)
                state = temp / "calls.jsonl"
                output = temp / "screens"
                apks = temp / "prebuilt apks"
                apks.mkdir()
                (apks / "app-qa.apk").write_bytes(b"fixture app")
                (apks / "app-qa-androidTest.apk").write_bytes(b"fixture tests")
                env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "WP01_PYTHON": sys.executable,
                       "WP01_FIXTURE_FILE": str(ROOT / "scripts/test-results/Wp01Fixtures.json"),
                       "WP01_FIXTURE_NAME": fixture, "WP01_FAKE_STATE": str(state)}
                result = subprocess.run(["bash", str(ROOT / "scripts/qa-smoke.sh"), str(temp / "prebuilt apks"),
                                         str(output), "--adb", str(fake_adb), "--serial", "emulator-5580"],
                                        env=env, text=True, capture_output=True, timeout=20)
                self.assertEqual(fixture == "pass", result.returncode == 0, result.stderr)
                report = json.loads((output / "Wp01-results.json").read_text())
                self.assertEqual(900, report["total_timeout_seconds"])
                self.assertEqual(120, report["artifact_reserve_seconds"])
                self.assertTrue(report["single_run"])
                self.assertEqual(1, len(report["attempts"]))
                self.assertNotIn("RETRIED", report["results"].values())
                calls = [json.loads(line) for line in state.read_text().splitlines()]
                installs = [call for call in calls if "install" in call]
                self.assertEqual([str(temp / "prebuilt apks/app-qa.apk"),
                                  str(temp / "prebuilt apks/app-qa-androidTest.apk")], [call[-1] for call in installs])
                self.assertEqual(["-r", "-g"], installs[0][3:-1])
                self.assertEqual(["-r", "-t", "-g"], installs[1][3:-1])
                self.assertFalse(any("clear" in call or "uninstall" in call for call in calls))
                instrument = [call for call in calls if "instrument" in call]
                self.assertEqual(2, len(instrument))  # Log-only inventory, then one execution.
                self.assertTrue(any("log" in call for call in instrument))
                self.assertTrue(all(call[-1] == "dev.lumenara.tv.qa.test/androidx.test.runner.AndroidJUnitRunner"
                                    for call in instrument))
                self.assertIn("dev.lumenara.tv.Wp02OptimizedSmokeTest", instrument[0])
                self.assertTrue((output / "summary.txt").exists())
                hashes = (output / "apk-pair.sha256").read_text()
                self.assertIn("app-qa.apk", hashes)
                self.assertIn("app-qa-androidTest.apk", hashes)
                self.assertTrue(all("argv" in command for command in report["commands"]))

    def test_missing_qa_apk_marks_setup_failure_so_workflow_cannot_retry_it_as_boot_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            temp = Path(directory)
            result = subprocess.run(["bash", str(ROOT / "scripts/qa-smoke.sh"), str(temp / "missing"),
                                     str(temp / "screens")], capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("INFRA", (temp / "screens/summary.txt").read_text())


if __name__ == "__main__":
    unittest.main()
