"""Exercise WP15's real release shell with APK/NDK/signer fixtures, without Gradle."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
import zipfile

from Wp10NativeTest import elf


ROOT = Path(__file__).resolve().parents[4]
WORKFLOW = (ROOT / ".github/workflows/release.yml").read_text()
SPEC = importlib.util.spec_from_file_location("wp15_release_gate", ROOT / "scripts/test-results/Wp01ReleaseGate.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


def shell_step(name):
    step = WORKFLOW.split("      - name: " + name + "\n", 1)[1].split("\n      - ", 1)[0]
    return textwrap.dedent(step.split("        run: |\n", 1)[1])


class Wp15ReleaseTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="Wp15 release space ")
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.apks = self.directory / "app/build/outputs/apk/release"
        self.apks.mkdir(parents=True)
        self.reports = self.directory / "app/build/reports/wp10"
        mapping = self.directory / "app/build/outputs/mapping/release/mapping.txt"
        mapping.parent.mkdir(parents=True)
        mapping.write_text("fixture mapping")
        (self.directory / "app/build.gradle.kts").write_text((ROOT / "app/build.gradle.kts").read_text())
        for script in ("scripts/verify-native.py", "scripts/apk-size-report.py",
                       "scripts/test-results/Wp01ReleaseGate.py"):
            target = self.directory / script
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / script, target)
        self.ndk = self.directory / "sdk/ndk/28.2.13676358"
        self.ndk.mkdir(parents=True)
        (self.ndk / "source.properties").write_text("Pkg.Revision = 28.2.13676358\n")
        self.executable(self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip", """
            import shutil, sys
            if '--version' in sys.argv: print('WP15 fixture strip')
            else: shutil.copyfile(sys.argv[-1], sys.argv[sys.argv.index('-o') + 1])
        """)
        self.env = {**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "ANDROID_HOME": str(self.directory / "sdk"),
                    "WP10_NDK_VERSION": "28.2.13676358", "RUNNER_TEMP": str(self.directory),
                    "VERSION": re.search(r'versionName = "([^"]+)"', (ROOT / "app/build.gradle.kts").read_text())[1],
                    "GITHUB_EVENT_NAME": "push", "GITHUB_SHA": "abcdef1234567",
                    "KEYSTORE_B64": base64.b64encode(b"fixture only").decode(),
                    "KEYSTORE_PASSWORD": "fixture only", "KEY_ALIAS": "streamora",
                    "WP15_CERT": GATE.EXPECTED_CERT_SHA256}
        self.version_code = re.search(r'versionCode = (\d+)', (ROOT / "app/build.gradle.kts").read_text())[1]
        self.env["WP15_CODE"] = self.version_code
        self.executable(self.directory / "sdk/build-tools/36.0.0/apksigner", """
            import os, shutil, sys
            if sys.argv[1] == 'sign':
                shutil.copyfile(sys.argv[-1], sys.argv[sys.argv.index('--out') + 1])
            else:
                bad = os.environ.get('WP15_BAD_CERT_ABI', '')
                cert = '0' * 64 if bad and bad in sys.argv[-1] else os.environ['WP15_CERT']
                print('Verified using v2 scheme (APK Signature Scheme v2): true')
                print('Signer #1 certificate SHA-256 digest: ' + cert)
        """)
        self.executable(self.directory / "sdk/build-tools/36.0.0/aapt", """
            import os
            print("package: name='dev.lumenara.tv' versionCode='" + os.environ['WP15_CODE'] +
                  "' versionName='" + os.environ['VERSION'] + "' platformBuildVersionName='16'")
        """)
        self.make_apks()

    def executable(self, path, source):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("#!" + sys.executable + "\n" + textwrap.dedent(source))
        path.chmod(0o755)

    def make_apks(self, extra_arm32_payload=False):
        entries = {"armeabi-v7a": elf(32, 40), "arm64-v8a": elf(64, 183)}
        for target in (*entries, "universal"):
            with zipfile.ZipFile(self.apks / ("app-" + target + "-release-unsigned.apk"), "w") as apk:
                apk.writestr("AndroidManifest.xml", b"manifest fixture")
                apk.writestr("classes.dex", b"dex fixture")
                for abi, data in entries.items():
                    if target == "universal" or abi == target or (target == "armeabi-v7a" and extra_arm32_payload):
                        apk.writestr("lib/" + abi + "/libfixture.so", data)
                        merged = self.directory / ("app/build/intermediates/merged_native_libs/release/"
                                                   "mergeReleaseNativeLibs/out/lib/" + abi + "/libfixture.so")
                        merged.parent.mkdir(parents=True, exist_ok=True)
                        merged.write_bytes(data)

    def run_step(self, name, **env):
        return subprocess.run(["bash", "-c", shell_step(name)], cwd=self.directory,
                              env={**self.env, **env}, capture_output=True, text=True, timeout=30)

    def test_native_gate_accepts_exact_abi_sets_and_retains_three_reports(self):
        result = self.run_step("Verify release native stripping and record build evidence")
        self.assertEqual(0, result.returncode, result.stderr)
        for abi, expected in (("armeabi-v7a", ["armeabi-v7a"]), ("arm64-v8a", ["arm64-v8a"]),
                              ("universal", ["arm64-v8a", "armeabi-v7a"])):
            report = json.loads((self.reports / ("release-" + abi + "-native.json")).read_text())
            self.assertTrue(report["success"])
            self.assertEqual(expected, report["apk"]["abis"])
        self.assertEqual(3, len(list(self.reports.glob("*-unsigned-size.json"))))

    def test_native_gate_rejects_second_arm_payload_in_device_apk(self):
        self.make_apks(extra_arm32_payload=True)
        result = self.run_step("Verify release native stripping and record build evidence")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ABI set differs", result.stderr)

    def test_native_gate_rejects_missing_fallback_or_fourth_apk(self):
        fallback = self.apks / "app-universal-release-unsigned.apk"
        contents = fallback.read_bytes()
        fallback.unlink()
        self.assertNotEqual(0, self.run_step("Verify release native stripping and record build evidence").returncode)
        fallback.write_bytes(contents)
        (self.apks / "extra.apk").write_bytes(contents)
        self.assertNotEqual(0, self.run_step("Verify release native stripping and record build evidence").returncode)

    def test_signing_loop_checks_all_three_and_keeps_each_hash_and_size_report(self):
        result = self.run_step("Sign and verify")
        self.assertEqual(0, result.returncode, result.stderr)
        dist = self.directory / "dist"
        self.assertEqual(3, len(list(dist.glob("*.apk"))))
        self.assertEqual(1, len(list(dist.glob("*-universal.apk"))))
        self.assertEqual(3, len((dist / "SHA256SUMS.txt").read_text().splitlines()))
        checked = subprocess.run(["sha256sum", "--check", "dist/SHA256SUMS.txt"],
                                 cwd=self.directory, capture_output=True)
        self.assertEqual(0, checked.returncode)
        measured = self.run_step("Measure the signed APK")
        self.assertEqual(0, measured.returncode, measured.stderr)
        self.assertEqual(3, len(list(self.reports.glob("*-signed-size.json"))))
        self.assertFalse((self.directory / "release.p12").exists())

    def test_wrong_certificate_on_any_abi_fails_signing_gate(self):
        for abi in ("armeabi-v7a", "arm64-v8a", "universal"):
            with self.subTest(abi=abi):
                result = self.run_step("Sign and verify", WP15_BAD_CERT_ABI=abi)
                self.assertNotEqual(0, result.returncode)
                self.assertIn("original personal release certificate", result.stderr)

    def test_abi_version_code_offset_fails_signing_gate(self):
        result = self.run_step("Sign and verify", WP15_CODE=str(int(self.version_code) + 1))
        self.assertNotEqual(0, result.returncode)

    def test_manual_experiment_preserves_all_abi_suffixes(self):
        result = self.run_step("Sign and verify", GITHUB_EVENT_NAME="workflow_dispatch")
        self.assertEqual(0, result.returncode, result.stderr)
        for abi in ("armeabi-v7a", "arm64-v8a", "universal"):
            self.assertTrue((self.directory / "dist" / (
                "Lumenara-" + self.env["VERSION"] + "-exp-abcdef1-" + abi + ".apk")).exists())

    def test_publication_uploads_both_abi_apks_and_exactly_one_universal(self):
        signed = self.run_step("Sign and verify")
        self.assertEqual(0, signed.returncode, signed.stderr)
        self.executable(self.directory / "bin/gh", """
            import json, pathlib, sys
            for argument in sys.argv[1:]:
                if argument.endswith('.apk'):
                    assert pathlib.Path(argument).is_file(), argument
            print(json.dumps(sys.argv[1:]))
        """)
        # The existing provenance gate is covered by WP01; execute only the upload
        # command with fake gh so this test can never publish or access credentials.
        command = "gh release create" + shell_step("Publish to Lumenara-releases").split("gh release create", 1)[1]
        result = subprocess.run(["bash", "-e", "-c", command], cwd=self.directory,
                                env={**self.env, "PATH": str(self.directory / "bin") + ":" + os.environ["PATH"],
                                     "RELEASES_REPO": "o/r"}, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        assets = [argument for argument in json.loads(result.stdout) if argument.endswith(".apk")]
        self.assertEqual({"dist/Lumenara-" + self.env["VERSION"] + "-" + abi + ".apk"
                          for abi in ("armeabi-v7a", "arm64-v8a", "universal")}, set(assets))
        self.assertEqual(3, len(assets))

    def test_every_workflow_shell_block_parses(self):
        for index, block in enumerate(re.findall(r"(?m)^        run: \|\n((?:          .*\n|\n)+)", WORKFLOW)):
            # GitHub expressions are substituted before the runner starts Bash.
            block = re.sub(r"\$\{\{.*?\}\}", "fixture", textwrap.dedent(block))
            result = subprocess.run(["bash", "-n"], input=block, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, str(index) + result.stderr)


if __name__ == "__main__":
    unittest.main()
