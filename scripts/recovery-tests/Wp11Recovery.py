#!/usr/bin/env python3
"""WP11 emulator-only kill matrix and previous-debug-APK install -r survival check. No builds."""
import argparse
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time


class RecoveryError(Exception):
    pass


def validate_device(serial, qemu, abi, sdk):
    if not re.fullmatch(r"emulator-\d+", serial) or qemu != "1" or abi != "x86_64" or sdk not in ("28", "36"):
        raise RecoveryError("WP11 requires an explicit API28/API36 x86_64 emulator serial")


def validate_upgrade(previous, current, test, installed_version=None):
    if previous["package"] != current["package"] or not current["package"].endswith(".debug"):
        raise RecoveryError("Upgrade requires the same debug application ID")
    if test["package"] != current["package"] + ".test":
        raise RecoveryError("Unexpected instrumentation package")
    if len({previous["certificate"], current["certificate"], test["certificate"]}) != 1:
        raise RecoveryError("APK signer mismatch")
    if current["version"] < previous["version"]:
        raise RecoveryError("APK downgrade refused")
    if installed_version is not None and installed_version > previous["version"]:
        raise RecoveryError("Installed app is newer than baseline; use a separate emulator")


def gate_result(output, code, expected):
    # Reuse WP01's strict parser: PASS followed by crash/timeout/missing finish is never green.
    source = Path(__file__).resolve().parents[1] / "test-results" / "Wp01DeviceTests.py"
    spec = importlib.util.spec_from_file_location("wp11_wp01_gate", source)
    module = importlib.util.module_from_spec(spec)
    old_bytecode = sys.dont_write_bytecode
    try:
        sys.dont_write_bytecode = True
        spec.loader.exec_module(module)
    finally:
        sys.dont_write_bytecode = old_bytecode
    parsed = module.parse_instrumentation(output, code)
    if parsed["errors"] or set(parsed["results"]) != set(expected) or any(v != "PASS" for v in parsed["results"].values()):
        raise RecoveryError("Instrumentation did not finish with exactly the required PASS inventory")
    return parsed["results"]


class Runner:
    def __init__(self, args):
        self.args = args
        self.ends = time.monotonic() + args.timeout
        self.report = {"package": args.package, "serial": args.serial, "phases": []}

    def run(self, argv, cap=60):
        remaining = self.ends - time.monotonic()
        if remaining <= 0:
            raise RecoveryError("WP11 total deadline exhausted")
        try:
            return subprocess.run(argv, capture_output=True, text=True, timeout=min(cap, remaining), check=False)
        except subprocess.TimeoutExpired:
            raise RecoveryError("WP11 command timed out") from None

    def adb(self, *args, cap=60):
        return self.run([self.args.adb, "-s", self.args.serial, *args], cap)

    @staticmethod
    def require_success(result):
        if result.returncode != 0:
            # External stderr may contain endpoint/fixture data: do not print it.
            raise RecoveryError("WP11 external command failed")
        return result.stdout.strip()

    def device(self):
        props = [self.require_success(self.adb("shell", "getprop", key)) for key in
                 ("ro.kernel.qemu", "ro.product.cpu.abi", "ro.build.version.sdk")]
        validate_device(self.args.serial, *props)

    def apk(self, path):
        badging = self.require_success(self.run([self.args.aapt, "dump", "badging", str(path)]))
        identity = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
        if not identity:
            raise RecoveryError("Cannot read APK identity")
        certs = self.require_success(self.run([self.args.apksigner, "verify", "--print-certs", str(path)]))
        cert = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", certs)
        if len(cert) != 1:
            raise RecoveryError("Exactly one verified APK signer is required")
        return {"package": identity[1], "version": int(identity[2]), "certificate": cert[0].lower(),
                "sha256": hashlib.sha256(Path(path).read_bytes()).hexdigest()}

    def install(self, path):
        result = self.adb("install", "-r", "-t", str(path), cap=90)
        if result.returncode or "Success" not in result.stdout:
            raise RecoveryError("install -r failed; no uninstall, data clear or downgrade fallback is permitted")

    def instrument(self, phase, classes, expected, extra=()):
        result = self.adb("shell", "am", "instrument", "-w", "-r", "-e", "class", classes,
                          *extra, self.args.runner, cap=self.args.timeout)
        outcomes = gate_result(result.stdout + "\n" + result.stderr, result.returncode, expected)
        self.report["phases"].append({"phase": phase, "results": outcomes})
        print("PASS WP11 " + phase)

    @contextmanager
    def fixture_server(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path.startswith("/list"):
                    body = ("#EXTM3U\n#EXTINF:-1 tvg-id=\"wp11\" group-title=\"WP11\",WP11 channel\n"
                            f"http://127.0.0.1:{self.server.server_port}/stream?fixture=wp11-private").encode()
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                else:
                    self.send_error(404)

            def log_message(self, *_):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server.daemon_threads = True
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        port = f"tcp:{server.server_port}"
        try:
            self.require_success(self.adb("reverse", port, port))
            yield f"http://127.0.0.1:{server.server_port}/list"
        finally:
            # Cleanup gets its own short bound even if the test budget was exhausted.
            try:
                subprocess.run([self.args.adb, "-s", self.args.serial, "reverse", "--remove", port],
                               capture_output=True, timeout=5, check=False)
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)

    def execute(self):
        self.device()  # Before installation, binding, seeding, or any kill.
        current, test = self.apk(self.args.apk), self.apk(self.args.test_apk)
        if current["package"] != self.args.package or test["package"] != self.args.package + ".test":
            raise RecoveryError("APK identity does not match the explicit runner target")
        previous = self.apk(self.args.previous_apk) if self.args.previous_apk else current
        installed = self.require_success(self.adb("shell", "dumpsys", "package", self.args.package))
        version = re.search(r"\bversionCode=(\d+)", installed)
        validate_upgrade(previous, current, test, int(version[1]) if version else None)
        self.report["apks"] = {"previous": previous, "current": current, "test": test}
        if self.args.previous_apk:
            self.install(self.args.previous_apk)
            self.install(self.args.test_apk)
            method = "dev.lumenara.tv.UpgradeSurvivalTest#encryptedDiskFixtureSurvivesReopenOrApkReplacement"
            with self.fixture_server() as endpoint:
                self.instrument("previous-apk-seed", method, [method],
                                ("-e", "wp11UpgradePhase", "seed", "-e", "wp11Endpoint", endpoint))
                self.install(self.args.apk)
                self.instrument("install-r-survival", method, [method], ("-e", "wp11UpgradePhase", "verify"))
        else:
            self.install(self.args.apk)
            self.install(self.args.test_apk)
        if not self.args.upgrade_only:
            expected = ["dev.lumenara.tv.ProcessDeathRecoveryTest#" + name for name in (
                "restoreIsAtomicAtEveryDurabilityBoundary",
                "sourceRemovalPreservesRecoverableUserStateAtEveryBoundary",
                "sourceReadditionPreservesRecoverableUserStateAtEveryBoundary")]
            self.instrument("process-death-matrix", "dev.lumenara.tv.ProcessDeathRecoveryTest", expected)
            migrations = ["dev.lumenara.tv.MigrationTest#" + name for name in (
                "oldCatalogSurvivesSchemaUpgrade",
                "everyIntermediateAutoMigrationPreservesUserState",
                "currentSchemaReopensWithoutChangingUserState",
                "versionSevenUnrefreshedVersionsAreGroupedWithoutLosingStreamsOrMarks",
                "versionEightCatalogSurvivesCategoryAndProgrammeIndexes",
                "versionNineCatalogSurvivesRestoreJournalMigration",
                "versionTenRecomputesStaleProviderVersionKeys",
                "versionElevenAddsCarryIdentitiesWithoutChangingUserState",
                "versionTwelveOnlyAddsDdlAndKeepsFinishedVersionBackfillFinished")]
            self.instrument("schema-1-through-13", "dev.lumenara.tv.MigrationTest", migrations)
            if not self.args.previous_apk:
                method = "dev.lumenara.tv.UpgradeSurvivalTest#encryptedDiskFixtureSurvivesReopenOrApkReplacement"
                self.instrument("encrypted-disk-reopen", method, [method])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--upgrade-only", action="store_true")
    parser.add_argument("--package", default="dev.lumenara.tv.debug")
    parser.add_argument("--runner", default="dev.lumenara.tv.debug.test/androidx.test.runner.AndroidJUnitRunner")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--aapt", default="aapt")
    parser.add_argument("--apksigner", default="apksigner")
    parser.add_argument("--timeout", type=int, default=600)
    parser.add_argument("--report", type=Path, default=Path("/tmp/wp11-recovery-result.json"))
    args = parser.parse_args()
    if args.upgrade_only and not args.previous_apk:
        parser.error("--upgrade-only requires --previous-apk")
    if not 30 <= args.timeout <= 900:
        parser.error("--timeout must be between 30 and 900 seconds")
    # Do not accept a different runner package, which could accidentally target another app.
    if args.runner != args.package + ".test/androidx.test.runner.AndroidJUnitRunner":
        parser.error("runner must match package")
    for tool in (args.adb, args.aapt, args.apksigner):
        if not shutil.which(tool):
            parser.error("required Android SDK tool unavailable")
    for path in (args.apk, args.test_apk, args.previous_apk):
        if path is not None and not path.is_file():
            parser.error("APK file unavailable")
    runner = Runner(args)
    status = 0
    try:
        runner.execute()
        runner.report["status"] = "PASS"
    except RecoveryError as error:
        runner.report["status"] = "FAIL"
        runner.report["reason"] = str(error)
        print("FAIL " + str(error), file=sys.stderr)
        status = 1
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(runner.report, indent=2) + "\n", encoding="utf-8")
    return status


if __name__ == "__main__":
    sys.exit(main())
