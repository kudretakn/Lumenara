"""Run without Gradle: python3 -B app/src/test/python/Wp02ApkSizeReportTest.py"""

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile


ROOT = Path(__file__).resolve().parents[4]
SPEC = importlib.util.spec_from_file_location("wp02_apk_size_report", ROOT / "scripts/apk-size-report.py")
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class Wp02ApkSizeReportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def apk(self, name, dex=100, resources=100, abi="x86", native=100):
        path = self.directory / name
        with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_STORED) as apk:
            apk.writestr("AndroidManifest.xml", b"manifest")
            apk.writestr("classes.dex", b"D" * dex)
            apk.writestr("classes2.dex", b"D" * dex)
            apk.writestr("classes10.dex", b"D" * dex)
            apk.writestr("resources.arsc", b"R" * resources)
            apk.writestr("res/raw/fixture.svg", b"R" * resources)
            apk.writestr(f"lib/{abi}/libvlc.so", b"N" * native)
            apk.writestr("assets/fixture", b"asset")
            apk.writestr("META-INF/CERT.RSA", b"signature")
        return path

    def cli(self, *args):
        with contextlib.redirect_stdout(io.StringIO()) as stdout, contextlib.redirect_stderr(io.StringIO()) as stderr:
            code = REPORT.main([str(arg) for arg in args])
        return code, stdout.getvalue(), stderr.getvalue()

    def test_exact_bytes_multidex_resources_hash_and_zip_overhead(self):
        apk = self.apk("one.apk")
        report = REPORT.inspect_apk(apk)
        self.assertEqual(300, report["groups"]["dex"]["zip_bytes"])
        self.assertEqual(200, report["groups"]["resources"]["zip_bytes"])
        self.assertEqual(100, report["native_by_abi"]["x86"]["uncompressed_bytes"])
        self.assertEqual(hashlib.sha256(apk.read_bytes()).hexdigest(), report["sha256"])
        self.assertEqual(apk.stat().st_size, report["zip_payload_bytes"] + report["zip_headers_alignment_signing_bytes"])
        self.assertGreater(report["zip_headers_alignment_signing_bytes"], 0)

    def test_compressed_bytes_are_distinct_from_uncompressed_bytes(self):
        apk = self.apk("compressed.apk")
        with zipfile.ZipFile(apk, "a", compression=zipfile.ZIP_DEFLATED) as output:
            output.writestr("res/raw/repeated.txt", b"A" * 4096)
        size = REPORT.inspect_apk(apk)["groups"]["resources"]
        self.assertLess(size["zip_bytes"], size["uncompressed_bytes"])

    def test_smaller_same_abi_apk_passes_and_writes_json(self):
        baseline = self.apk("base.apk")
        candidate = self.apk("candidate.apk", dex=50, resources=50)
        output = self.directory / "reports/size.json"
        code, _, stderr = self.cli(candidate, "--baseline", baseline, "--require-reduction", "--output", output)
        self.assertEqual(0, code, stderr)
        comparison = json.loads(output.read_text())["comparison"]
        self.assertEqual(250, comparison["dex_resource_zip_bytes_saved"])
        self.assertTrue(comparison["native_entries_unchanged"])

    def test_native_only_shrink_is_not_r8_savings_and_fails_gate(self):
        baseline = self.apk("base.apk")
        candidate = self.apk("native-only.apk", native=1)
        code, stdout, _ = self.cli(candidate, "--baseline", baseline, "--require-reduction")
        self.assertEqual(1, code)
        comparison = json.loads(stdout)["comparison"]
        self.assertEqual(0, comparison["dex_resource_zip_bytes_saved"])
        self.assertLess(comparison["apk_bytes_delta"], 0)
        self.assertFalse(comparison["native_entries_unchanged"])

    def test_equal_total_native_size_still_detects_changed_payload(self):
        baseline = REPORT.inspect_apk(self.apk("base.apk"))
        candidate = REPORT.inspect_apk(self.apk("candidate.apk"))
        candidate["native_entries"]["lib/x86/libvlc.so"]["sha256"] = "changed"
        comparison = REPORT.compare(baseline, candidate)
        self.assertEqual(0, comparison["groups_delta"]["native"]["zip_bytes"])
        self.assertEqual(["lib/x86/libvlc.so"], comparison["changed_native_entries"])

    def test_different_abi_sets_are_rejected(self):
        baseline = self.apk("arm.apk", abi="armeabi-v7a")
        candidate = self.apk("x86.apk", dex=1, resources=1)
        code, stdout, stderr = self.cli(candidate, "--baseline", baseline)
        self.assertEqual(2, code)
        self.assertEqual("", stdout)
        self.assertIn("ABI mismatch", stderr)

    def test_resource_growth_fails_even_if_apk_and_dex_shrink(self):
        baseline = self.apk("base.apk", dex=1000)
        candidate = self.apk("candidate.apk", dex=1, resources=101)
        code, _, _ = self.cli(candidate, "--baseline", baseline, "--require-reduction")
        self.assertEqual(1, code)

    def test_duplicate_zip_entries_are_rejected(self):
        apk = self.apk("duplicate.apk")
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(apk, "a") as output:
                output.writestr("classes.dex", b"other")
        self.assertEqual(2, self.cli(apk)[0])

    def test_corrupt_and_non_apk_archives_are_rejected(self):
        path = self.directory / "invalid.apk"
        path.write_bytes(b"not a zip")
        self.assertEqual(2, self.cli(path)[0])
        with zipfile.ZipFile(path, "w") as output:
            output.writestr("arbitrary-file", b"not an APK")
        self.assertEqual(2, self.cli(path)[0])

    def test_output_cannot_overwrite_candidate_or_baseline(self):
        baseline = self.apk("base.apk")
        candidate = self.apk("candidate.apk")
        for output in (candidate, baseline):
            before = output.read_bytes()
            self.assertEqual(2, self.cli(candidate, "--baseline", baseline, "--output", output)[0])
            self.assertEqual(before, output.read_bytes())

    def test_reduction_gate_requires_baseline(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            REPORT.main([str(self.apk("one.apk")), "--require-reduction"])
        self.assertEqual(2, error.exception.code)


if __name__ == "__main__":
    unittest.main()
