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

    def test_qa_launcher_uses_prebuilt_apks_strict_gate_inventory_and_shared_deadline(self):
        for fixture in ("pass", "pass_then_crash", "missing_finish", "unexpected_skip", "only_retried"):
            with self.subTest(fixture=fixture), tempfile.TemporaryDirectory(prefix="Wp10 QA space ") as directory:
                temp = Path(directory)
                fake_adb = temp / "fake adb"
                fake_adb.write_text("#!" + sys.executable + "\n" +
                                    (ROOT / "scripts/test-results/Wp01FakeAdb.py").read_text().split("\n", 1)[1])
                fake_adb.chmod(0o755)
                state = temp / "calls.jsonl"
                output = temp / "screens"
                env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "WP01_PYTHON": sys.executable,
                       "WP01_FIXTURE_FILE": str(ROOT / "scripts/test-results/Wp01Fixtures.json"),
                       "WP01_FIXTURE_NAME": fixture, "WP01_FAKE_STATE": str(state)}
                result = subprocess.run(["bash", str(ROOT / "scripts/qa-smoke.sh"), str(temp / "prebuilt apks"),
                                         str(output), "--adb", str(fake_adb), "--serial", "emulator-5580"],
                                        env=env, text=True, capture_output=True, timeout=20)
                self.assertEqual(fixture in ("pass", "only_retried"), result.returncode == 0, result.stderr)
                report = json.loads((output / "Wp01-results.json").read_text())
                self.assertEqual(600, report["total_timeout_seconds"])
                self.assertEqual(120, report["artifact_reserve_seconds"])
                calls = [json.loads(line) for line in state.read_text().splitlines()]
                installs = [call for call in calls if "install" in call]
                self.assertEqual([str(temp / "prebuilt apks/app-qa.apk"),
                                  str(temp / "prebuilt apks/app-qa-androidTest.apk")], [call[-1] for call in installs])
                instrument = [call for call in calls if "instrument" in call]
                self.assertTrue(any("log" in call for call in instrument))
                self.assertTrue(all(call[-1] == "dev.lumenara.tv.qa.test/androidx.test.runner.AndroidJUnitRunner"
                                    for call in instrument))
                self.assertIn("dev.lumenara.tv.Wp02OptimizedSmokeTest", instrument[0])
                self.assertTrue((output / "summary.txt").exists())


if __name__ == "__main__":
    unittest.main()
