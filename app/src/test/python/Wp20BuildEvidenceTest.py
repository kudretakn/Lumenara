"""Exercise build evidence orchestration without starting Gradle or a compiler."""
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
SPEC = importlib.util.spec_from_file_location("wp20_build", ROOT / "gradle/Wp20BuildEvidence.py")
BUILD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILD)


class Wp20BuildEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="Wp20 build ")
        self.addCleanup(self.temp.cleanup)
        self.output = Path(self.temp.name) / "evidence"
        self.calls = []
        self.failure = None
        self.timeout = False
        self.reuse = True
        self.tick = 0

    def timer(self):
        self.tick += 1
        return self.tick

    def fake_gradle(self, command, **kwargs):
        # This callback never starts a process. Assert orchestration, not Gradle output truth.
        self.calls.append(command)
        if self.timeout:
            raise subprocess.TimeoutExpired(command, 3600)
        log = kwargs["stdout"]
        if command[-1] == "clean":
            log.write("> Task :app:clean\n")
        else:
            log.write("> Task :app:test UP-TO-DATE\n> Task :core:catalog:test FROM-CACHE\n> Task :app:assembleDebug\n")
        if self.reuse and "--configuration-cache" in command:
            log.write("Reusing configuration cache.\n")
        return subprocess.CompletedProcess(command, self.failure or 0)

    def measure(self, scope="debug", compare=False):
        return BUILD.measure(scope, self.output, compare, ROOT, self.fake_gradle, self.timer)

    def report(self):
        return json.loads((self.output / "timings.json").read_text())

    def test_normal_build_preserves_entire_existing_debug_scope(self):
        self.assertEqual(0, self.measure())
        self.assertEqual(["test", "lintDebug", ":app:assembleDebug", ":app:assembleDebugAndroidTest"], self.calls[0][-4:])
        self.assertIn("--dependency-verification=strict", self.calls[0])
        self.assertEqual(1, len(self.calls))
        self.assertTrue((self.output / "ordinary.log").is_file())

    def test_optimized_scope_keeps_qa_instrumentation_and_release(self):
        self.assertEqual(0, self.measure("optimized"))
        self.assertEqual(["-Pwp02.testBuildType=qa", ":app:assembleQa", ":app:assembleQaAndroidTest", ":app:assembleRelease"],
                         self.calls[0][-4:])

    def test_comparison_uses_identical_full_tasks_after_each_clean_and_isolated_cache(self):
        self.assertEqual(0, self.measure(compare=True))
        self.assertEqual(8, len(self.calls))
        self.assertTrue(all(command[-1] == "clean" for command in self.calls[::2]))
        builds = self.calls[1::2]
        self.assertTrue(all(command[-4:] == BUILD.SCOPES["debug"] for command in builds))
        self.assertTrue(all("--no-build-cache" in command and "--no-configuration-cache" in command for command in builds[:2]))
        self.assertTrue(all("--build-cache" in command and "--configuration-cache" in command for command in builds[2:]))
        task_cache = {arg for command in self.calls for arg in command if arg.startswith("-Pwp20.buildCacheDir=")}
        self.assertEqual(1, len(task_cache))
        samples = self.report()["samples"]
        self.assertEqual(["off-1", "off-2", "on-1", "on-2"], [s["label"] for s in samples if not s["label"].endswith("-clean")])
        self.assertTrue(all(s["seconds"] == 1 for s in samples))
        self.assertTrue((self.output / "timings.md").is_file())

    def test_build_or_clean_failure_stops_comparison_and_retains_evidence(self):
        self.failure = 7
        self.assertEqual(7, self.measure(compare=True))
        self.assertEqual(1, len(self.calls))
        self.assertEqual(7, self.report()["samples"][0]["exit_code"])
        self.assertEqual(7, self.measure())
        self.assertTrue((self.output / "ordinary.log").exists())

    def test_timeout_fails_with_log_and_timing_artifacts(self):
        self.timeout = True
        self.assertEqual(124, self.measure())
        self.assertEqual(124, self.report()["samples"][0]["exit_code"])

    def test_missing_configuration_reuse_is_a_failed_comparison(self):
        self.reuse = False
        self.assertEqual(1, self.measure(compare=True))
        self.assertIn("did not reuse", self.report()["error"])

    def test_task_state_counts_do_not_treat_console_summaries_as_executed_tasks(self):
        counts = BUILD.task_counts("> Task :a FROM-CACHE\n> Task :b UP-TO-DATE\n> Task :c NO-SOURCE\n> Task :d SKIPPED\n"
                                   "> Task :e\n> Task :f FAILED\n6 actionable tasks: 2 executed, 1 from cache\n")
        self.assertEqual({"FROM-CACHE": 1, "UP-TO-DATE": 1, "NO-SOURCE": 1, "SKIPPED": 1, "executed": 2}, counts)


if __name__ == "__main__":
    unittest.main()
