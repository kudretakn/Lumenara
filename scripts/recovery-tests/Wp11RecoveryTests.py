import unittest
from Wp11Recovery import RecoveryError, gate_result, validate_device, validate_upgrade


class Wp11RecoveryTests(unittest.TestCase):
    def test_only_explicit_emulators_are_allowed(self):
        validate_device("emulator-5580", "1", "x86_64", "36")
        validate_device("emulator-5580", "1", "x86_64", "28")
        for values in (("192.168.1.14:5555", "1", "x86_64", "36"),
                       ("emulator-5580", "0", "x86_64", "36"),
                       ("emulator-5580", "1", "arm64-v8a", "36"),
                       ("emulator-5580", "1", "x86_64", "29")):
            with self.subTest(values=values), self.assertRaises(RecoveryError):
                validate_device(*values)

    def fixtures(self):
        old = dict(package="dev.lumenara.tv.debug", certificate="abc", version=20)
        new = dict(old, version=21)
        test = dict(new, package="dev.lumenara.tv.debug.test")
        return old, new, test

    def test_same_identity_and_signer_are_required(self):
        old, new, test = self.fixtures()
        validate_upgrade(old, new, test, 19)
        for changed in (dict(new, package="dev.lumenara.tv"), dict(new, certificate="other")):
            with self.assertRaises(RecoveryError):
                validate_upgrade(old, changed, test)
        with self.assertRaises(RecoveryError):
            validate_upgrade(old, new, dict(test, certificate="other"))

    def test_no_downgrade_of_either_installed_or_baseline_apk(self):
        old, new, test = self.fixtures()
        for previous, current, installed in ((old, dict(new, version=19), None), (old, new, 21)):
            with self.assertRaises(RecoveryError):
                validate_upgrade(previous, current, test, installed)

    def transcript(self):
        return "\n".join((
            "INSTRUMENTATION_STATUS: class=dev.lumenara.tv.UpgradeSurvivalTest",
            "INSTRUMENTATION_STATUS: test=survives",
            "INSTRUMENTATION_STATUS: numtests=1",
            "INSTRUMENTATION_STATUS_CODE: 0", "OK (1 test)", "INSTRUMENTATION_CODE: -1"))

    def test_finished_inventory_is_required(self):
        expected = ["dev.lumenara.tv.UpgradeSurvivalTest#survives"]
        gate_result(self.transcript(), 0, expected)
        for output, code in ((self.transcript() + "\nINSTRUMENTATION_FAILED: Process crashed", 1),
                             (self.transcript().replace("INSTRUMENTATION_CODE: -1", ""), 0), ("", 0)):
            with self.subTest(code=code), self.assertRaises(RecoveryError):
                gate_result(output, code, expected)
        with self.assertRaises(RecoveryError):
            gate_result(self.transcript(), 0, ["different#test"])


if __name__ == "__main__":
    unittest.main()
