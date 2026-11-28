"""Fail closed before Gradle configures any profile installation/instrumentation task."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys


TOKEN = "WP20_API36_EMULATOR_ALLOWED"
SERIAL = re.compile(r"emulator-[0-9]+", re.ASCII)


def allowed_serials(path):
    entries = {line.split("#", 1)[0].strip() for line in Path(path).read_text().splitlines()}
    entries.discard("")
    if not entries or any(not SERIAL.fullmatch(entry) for entry in entries):
        raise ValueError("WP20 requires a nonempty emulator-only serial allowlist")
    return entries


def verify(serial, allowlist, run=subprocess.run):
    if not SERIAL.fullmatch(serial) or serial not in allowed_serials(allowlist):
        raise ValueError("WP20 profile serial is not an explicitly allow-listed emulator")
    if os.environ.get("ANDROID_SERIAL") != serial:
        raise ValueError("WP20 ANDROID_SERIAL must equal the selected allow-listed serial")
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    candidate = Path(sdk) / "platform-tools/adb" if sdk else None
    adb = str(candidate) if candidate and candidate.is_file() else "adb"

    def query(*args):
        result = run([adb, "-s", serial, *args], check=True, capture_output=True, text=True, timeout=15)
        return result.stdout.strip()

    if query("get-state") != "device":
        raise ValueError("WP20 emulator is not online")
    expected = {"ro.kernel.qemu": "1", "ro.build.version.sdk": "36",
                "ro.product.cpu.abi": "x86_64", "sys.boot_completed": "1"}
    for key, value in expected.items():
        if query("shell", "getprop", key) != value:
            raise ValueError("WP20 requires a booted API36 x86_64 emulator: " + key)
    if "feature:android.software.leanback" not in query("shell", "pm", "list", "features").splitlines():
        raise ValueError("WP20 requires an Android TV emulator")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allowlist", required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        verify(args.serial, args.allowlist)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print("WP20 profile gate rejected: " + str(error), file=sys.stderr)
        return 1
    print(TOKEN)
    return 0


if __name__ == "__main__":
    sys.exit(main())
