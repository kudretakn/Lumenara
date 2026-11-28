"""WP10 aggregate verdict and the actual prebuilt QA launcher, with fake adb only."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[4]
SPEC = importlib.util.spec_from_file_location("wp10_gate", ROOT / "scripts/Wp10DeviceGate.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class Wp10DeviceGateTest(unittest.TestCase):
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
        for needs, success in (({name: {"result": "success"} for name in GATE.REQUIRED}, True), ({}, False)):
            result = subprocess.run([sys.executable, "-B", str(ROOT / "scripts/Wp10DeviceGate.py")],
                                    env={**os.environ, "WP10_NEEDS": json.dumps(needs)}, capture_output=True)
            self.assertEqual(success, result.returncode == 0)

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
