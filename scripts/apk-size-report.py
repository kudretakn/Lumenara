#!/usr/bin/env python3
"""Report exact APK/DEX/resource/native bytes, optionally against a same-ABI APK.

Example (lead supplies builds of the base and candidate with the same ABI set):
  python3 scripts/apk-size-report.py candidate.apk --baseline base.apk \
      --require-reduction --output build/reports/wp02/apk-size.json

ZIP payload sizes exclude headers, alignment and the APK signing block; those are
reported separately. Native deltas never count toward DEX/resource savings. Match
toolchain, signing, variant, source inputs and ABI set for meaningful comparisons;
debug versus release is not an R8 A/B measurement. This does not measure startup.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile


GROUPS = ("dex", "resources", "native", "assets", "manifest", "meta_inf", "other")


def digest(stream):
    result = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        result.update(chunk)
    return result.hexdigest()


def category(name):
    if re.fullmatch(r"classes(?:[2-9]|[1-9][0-9]+)?\.dex", name):
        return "dex"
    if name == "resources.arsc" or name.startswith("res/"):
        return "resources"
    if name.startswith("lib/"):
        return "native"
    if name.startswith("assets/"):
        return "assets"
    if name == "AndroidManifest.xml":
        return "manifest"
    if name.startswith("META-INF/"):
        return "meta_inf"
    return "other"


def empty_size():
    return {"entries": 0, "zip_bytes": 0, "uncompressed_bytes": 0}


def add_entry(size, entry):
    size["entries"] += 1
    size["zip_bytes"] += entry.compress_size
    size["uncompressed_bytes"] += entry.file_size


def inspect_apk(path):
    path = Path(path)
    groups = {name: empty_size() for name in GROUPS}
    native_abis = {}
    native_entries = {}
    with zipfile.ZipFile(path) as apk:
        entries = apk.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError(f"duplicate ZIP entry in {path}")
        if "AndroidManifest.xml" not in names:
            raise ValueError(f"missing AndroidManifest.xml in {path}")
        for entry in entries:
            if entry.is_dir():
                continue
            group = category(entry.filename)
            add_entry(groups[group], entry)
            if group == "native":
                parts = entry.filename.split("/")
                if len(parts) != 3 or not parts[1] or not parts[2]:
                    raise ValueError(f"invalid native entry {entry.filename!r} in {path}")
                add_entry(native_abis.setdefault(parts[1], empty_size()), entry)
                with apk.open(entry) as stream:
                    native_entries[entry.filename] = {
                        "sha256": digest(stream),
                        "zip_bytes": entry.compress_size,
                        "uncompressed_bytes": entry.file_size,
                    }
        if groups["dex"]["entries"] == 0:
            raise ValueError(f"missing classes DEX in {path}")
    with path.open("rb") as stream:
        sha256 = digest(stream)
    payload = sum(size["zip_bytes"] for size in groups.values())
    apk_bytes = path.stat().st_size
    return {
        "path": str(path),
        "sha256": sha256,
        "apk_bytes": apk_bytes,
        "apk_MB": apk_bytes / 1_000_000,
        "apk_MiB": apk_bytes / 1_048_576,
        "zip_payload_bytes": payload,
        "zip_headers_alignment_signing_bytes": apk_bytes - payload,
        "groups": groups,
        "abis": sorted(native_abis),
        "native_by_abi": dict(sorted(native_abis.items())),
        "native_entries": dict(sorted(native_entries.items())),
    }


def compare(baseline, candidate):
    if baseline["abis"] != candidate["abis"]:
        raise ValueError(
            f"ABI mismatch: baseline={baseline['abis']}, candidate={candidate['abis']}; "
            "compare APKs with identical ABI sets"
        )
    groups = {
        name: {
            key: candidate["groups"][name][key] - baseline["groups"][name][key]
            for key in ("entries", "zip_bytes", "uncompressed_bytes")
        }
        for name in GROUPS
    }
    native_names = set(baseline["native_entries"]) | set(candidate["native_entries"])
    changed_native = sorted(
        name for name in native_names
        if baseline["native_entries"].get(name) != candidate["native_entries"].get(name)
    )
    return {
        "delta_convention": "candidate minus baseline; negative means smaller",
        "apk_bytes_delta": candidate["apk_bytes"] - baseline["apk_bytes"],
        "groups_delta": groups,
        "dex_resource_zip_bytes_saved": -sum(
            groups[name]["zip_bytes"] for name in ("dex", "resources")
        ),
        "native_entries_unchanged": not changed_native,
        "changed_native_entries": changed_native,
        "native_bytes_excluded_from_dex_resource_savings": True,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("apk", type=Path, help="candidate APK")
    parser.add_argument("--baseline", type=Path, help="unoptimized APK with the same ABI set")
    parser.add_argument("--output", type=Path, help="write JSON here instead of stdout")
    parser.add_argument("--require-reduction", action="store_true",
                        help="exit 1 unless APK, DEX and resources each shrink (requires --baseline)")
    args = parser.parse_args(argv)
    if args.require_reduction and args.baseline is None:
        parser.error("--require-reduction requires --baseline")
    try:
        # Never replace either input APK with a report, including through a symlink.
        if args.output and args.output.resolve() in {
            path.resolve() for path in (args.apk, args.baseline) if path is not None
        }:
            raise ValueError("--output must not overwrite an input APK")
        report = {"schema_version": 1, "units": "bytes; MB=1000000 bytes; MiB=1048576 bytes",
                  "candidate": inspect_apk(args.apk)}
        if args.baseline is not None:
            report["baseline"] = inspect_apk(args.baseline)
            report["comparison"] = compare(report["baseline"], report["candidate"])
        result = json.dumps(report, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(result, encoding="utf-8")
        else:
            sys.stdout.write(result)
        if args.require_reduction:
            change = report["comparison"]
            if (change["apk_bytes_delta"] >= 0 or any(
                change["groups_delta"][name]["zip_bytes"] >= 0 for name in ("dex", "resources")
            )):
                print("APK, DEX and resource ZIP bytes must each decrease; see report", file=sys.stderr)
                return 1
        return 0
    except (OSError, ValueError, zipfile.BadZipFile, RuntimeError) as error:
        print(f"apk-size-report: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
