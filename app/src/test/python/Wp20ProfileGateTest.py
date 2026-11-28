"""No adb or Gradle: exercise WP20's actual profile gate with controlled device responses."""
import importlib.util
import contextlib
import io
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[4]
SPEC = importlib.util.spec_from_file_location("wp20_profile_gate", ROOT / "baselineprofile/Wp20ProfileGate.py")
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class Wp20ProfileGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="Wp20 gate ")
        self.addCleanup(self.temp.cleanup)
        self.allowlist = Path(self.temp.name) / "serials.txt"
        self.allowlist.write_text("# Only this explicit AVD\nemulator-5580\n")
        self.serial = "emulator-5580"
        self.environment = patch.dict(os.environ, {"ANDROID_SERIAL": self.serial})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.calls = []
        self.responses = {("get-state",): "device", ("shell", "getprop", "ro.kernel.qemu"): "1",
                          ("shell", "getprop", "ro.build.version.sdk"): "36",
                          ("shell", "getprop", "ro.product.cpu.abi"): "x86_64",
                          ("shell", "getprop", "sys.boot_completed"): "1",
                          ("shell", "pm", "list", "features"): "feature:android.software.leanback"}

    def adb(self, command, **kwargs):
        self.calls.append(command)
        self.assertEqual(["-s", self.serial], command[1:3])
        self.assertTrue(kwargs["check"])
        self.assertLessEqual(kwargs["timeout"], 15)
        return subprocess.CompletedProcess(command, 0, self.responses[tuple(command[3:])] + "\n", "")

    def verify(self):
        GATE.verify(self.serial, self.allowlist, self.adb)

    def test_allowlisted_booted_api36_tv_emulator_is_the_only_adb_target(self):
        self.verify()
        self.assertEqual(6, len(self.calls))
        self.assertTrue(all(command[1:3] == ["-s", "emulator-5580"] for command in self.calls))
        self.assertFalse(any("install" in command for command in self.calls))

    def test_physical_ip_usb_unlisted_and_injected_serials_fail_before_adb(self):
        for serial in ("192.168.1.14:5555", "owner-tv-usb", "emulator-5554", "emulator-5580;bad", ""):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                GATE.verify(serial, self.allowlist, self.adb)
        self.assertFalse(self.calls)

    def test_missing_or_different_android_serial_fails_before_adb(self):
        for value in (None, "emulator-5554", "192.168.1.14:5555"):
            with self.subTest(value=value), patch.dict(os.environ):
                if value is None:
                    os.environ.pop("ANDROID_SERIAL", None)
                else:
                    os.environ["ANDROID_SERIAL"] = value
                with self.assertRaises(ValueError):
                    self.verify()
        self.assertFalse(self.calls)

    def test_empty_wildcard_or_physical_allowlist_is_rejected_before_adb(self):
        for entries in ("# empty", "emulator-*", "192.168.1.14:5555", "emulator-5580\nowner-tv-usb"):
            with self.subTest(entries=entries):
                self.allowlist.write_text(entries)
                with self.assertRaises(ValueError):
                    self.verify()
        self.assertFalse(self.calls)

    def test_wrong_api_abi_physical_device_and_incomplete_boot_fail(self):
        for prop, wrong in (("ro.build.version.sdk", "28"), ("ro.product.cpu.abi", "arm64-v8a"),
                            ("ro.kernel.qemu", "0"), ("sys.boot_completed", "0")):
            with self.subTest(prop=prop):
                old = self.responses[("shell", "getprop", prop)]
                self.responses[("shell", "getprop", prop)] = wrong
                with self.assertRaises(ValueError):
                    self.verify()
                self.responses[("shell", "getprop", prop)] = old

    def test_non_tv_and_offline_devices_are_rejected(self):
        self.responses[("shell", "pm", "list", "features")] = "feature:android.hardware.type.televisionish"
        with self.assertRaises(ValueError):
            self.verify()
        self.responses[("get-state",)] = "offline"
        with self.assertRaises(ValueError):
            self.verify()

    def test_adb_error_or_timeout_is_not_swallowed(self):
        for error in (subprocess.CalledProcessError(1, ["adb"]), subprocess.TimeoutExpired(["adb"], 15)):
            def failed(*args, **kwargs):
                raise error
            with self.subTest(error=type(error)), self.assertRaises(subprocess.SubprocessError):
                GATE.verify(self.serial, self.allowlist, failed)

    def test_cli_rejection_has_nonzero_exit_and_never_emits_allow_token(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = GATE.main(["--serial", "192.168.1.14:5555", "--allowlist", str(self.allowlist)])
        self.assertEqual(1, result)
        self.assertNotIn(GATE.TOKEN, stdout.getvalue())
        self.assertIn("profile gate rejected", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
