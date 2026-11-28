#!/usr/bin/env python3
"""WP10: audit packaged ELF symbols/bytes and verify the pinned NDK strip tool.

Read-only for APKs and merged libraries; llvm-strip runs only on temporary copies.
No third-party Python packages, Gradle, signing or historical size assumptions.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile


NDK_VERSION = "28.2.13676358"
ABI_ELF = {"armeabi-v7a": (32, 40), "arm64-v8a": (64, 183),
           "x86": (32, 3), "x86_64": (64, 62)}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1_048_576), b""):
            digest.update(block)
    return digest.hexdigest()


def debug_section(name):
    return name.startswith((".debug", ".zdebug", ".stab")) or name in {
        ".symtab", ".strtab", ".gnu_debugdata", ".gdb_index"
    }


def inspect_elf(data):
    """Parse section sizes directly (ELF32/ELF64), keeping .dynsym distinct."""
    if len(data) < 16 or data[:4] != b"\x7fELF" or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError("Invalid ELF identification")
    bits = {1: 32, 2: 64}[data[4]]
    endian = "<" if data[5] == 1 else ">"

    def unpack(fmt, offset):
        size = struct.calcsize(endian + fmt)
        if offset < 0 or offset + size > len(data):
            raise ValueError("Truncated ELF structure")
        return struct.unpack_from(endian + fmt, data, offset)

    header = unpack("HHIIIIIHHHHHH" if bits == 32 else "HHIQQQIHHHHHH", 16)
    if header[0] != 3 or header[2] != 1:  # ET_DYN, EV_CURRENT
        raise ValueError("Expected an ELF shared library")
    offset, stride, count, names_index = header[5], header[10], header[11], header[12]
    fmt = "IIIIIIIIII" if bits == 32 else "IIQQQQIIQQ"
    if not offset or stride != struct.calcsize(endian + fmt):
        raise ValueError("Missing/invalid ELF section table")
    first = unpack(fmt, offset)
    count = count or first[5]  # ELF extended section counts
    names_index = first[6] if names_index == 0xffff else names_index
    if count < 2 or offset + count * stride > len(data) or not 0 < names_index < count:
        raise ValueError("Invalid ELF section count/name table")
    headers = [unpack(fmt, offset + index * stride) for index in range(count)]

    def content(section):
        start, size = section[4], section[5]
        if start + size > len(data):
            raise ValueError("Truncated ELF section")
        return data[start:start + size]

    names = content(headers[names_index])
    sections = []
    for section in headers:
        start = section[0]
        end = names.find(b"\0", start)
        if start >= len(names) or end < 0:
            raise ValueError("Invalid ELF section name")
        name = names[start:end].decode("ascii")
        if section[1] != 8:  # SHT_NOBITS (.bss) occupies memory, not file bytes
            content(section)
        row = {"name": name, "type": section[1], "bytes": section[5],
               "file_bytes": 0 if section[1] == 8 else section[5]}
        if section[1] in (2, 11):  # SHT_SYMTAB / SHT_DYNSYM
            expected_stride = 16 if bits == 32 else 24
            if section[9] != expected_stride or section[5] % expected_stride:
                raise ValueError("Invalid ELF symbol table")
            row["symbols"] = section[5] // expected_stride
        sections.append(row)
    dynamic = [row for row in sections if row["type"] == 11]
    if len(dynamic) != 1 or dynamic[0]["name"] != ".dynsym" or dynamic[0]["symbols"] < 1:
        raise ValueError("Missing ELF runtime dynamic symbols")
    return {"bits": bits, "machine": header[1], "sections": sections,
            "dynamic_symbols": dynamic[0]["symbols"],
            "dynamic_symbol_bytes": dynamic[0]["bytes"],
            "debug_static_symbol_bytes": sum(row["file_bytes"] for row in sections if debug_section(row["name"]))}


def strip_tool(ndk):
    properties = (ndk / "source.properties").read_text(encoding="utf-8")
    if not re.search(r"^Pkg\.Revision\s*=\s*" + re.escape(NDK_VERSION) + r"\s*$", properties, re.M):
        raise ValueError("Expected NDK " + NDK_VERSION)
    # CI runs on Linux x86_64. Never fall back to PATH or another installed NDK.
    tool = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
    version = subprocess.check_output([str(tool), "--version"], text=True, timeout=30).strip()
    return tool, {"ndk_version": NDK_VERSION, "path": str(tool), "sha256": sha256(tool), "version": version}


def inspect_apk(apk_path, tool, merged_libs, required_abis):
    errors, libraries, abis = [], [], set()
    with zipfile.ZipFile(apk_path) as apk, tempfile.TemporaryDirectory(prefix="Wp10-native-") as directory:
        entries = apk.infolist()
        if len({entry.filename for entry in entries}) != len(entries):
            raise ValueError("Duplicate APK ZIP entries")
        if "AndroidManifest.xml" not in apk.namelist() or "classes.dex" not in apk.namelist():
            raise ValueError("Expected an APK with a manifest and DEX")
        for entry in sorted(entries, key=lambda value: value.filename):
            if not entry.filename.startswith("lib/") or not entry.filename.endswith(".so"):
                continue
            parts = entry.filename.split("/")
            if len(parts) != 3 or parts[1] not in ABI_ELF or not re.fullmatch(r"[A-Za-z0-9_.+-]+\.so", parts[2]):
                raise ValueError("Invalid native entry: " + entry.filename)
            abi = parts[1]
            abis.add(abi)
            row = {"entry": entry.filename, "abi": abi, "zip_bytes": entry.compress_size,
                   "bytes": entry.file_size}
            libraries.append(row)
            try:
                data = apk.read(entry)
                row["sha256"] = hashlib.sha256(data).hexdigest()
                row["elf"] = inspect_elf(data)
                if (row["elf"]["bits"], row["elf"]["machine"]) != ABI_ELF[abi]:
                    raise ValueError("ELF architecture differs from APK ABI")
                if row["elf"]["debug_static_symbol_bytes"]:
                    errors.append(entry.filename + ": packaged debug/static symbol payload remains")
                if merged_libs is not None:
                    source = merged_libs / abi / parts[2]
                    source_data = source.read_bytes()
                    source_elf = inspect_elf(source_data)
                    row["before_strip"] = {"bytes": len(source_data), "sha256": hashlib.sha256(source_data).hexdigest(),
                                           "elf": source_elf}
                    row["bytes_removed"] = len(source_data) - len(data)
                    if (source_elf["bits"], source_elf["machine"]) != ABI_ELF[abi]:
                        raise ValueError("Merged ELF architecture differs from APK ABI")
                    if source_elf["dynamic_symbols"] != row["elf"]["dynamic_symbols"]:
                        raise ValueError("Stripping changed runtime dynamic symbol count")
                original = Path(directory) / "packaged.so"
                stripped = Path(directory) / "stripped.so"
                original.write_bytes(data)
                subprocess.run([str(tool), "--strip-unneeded", "-o", str(stripped), str(original)],
                               check=True, capture_output=True, timeout=60)
                stripped_elf = inspect_elf(stripped.read_bytes())
                row["strip_probe_bytes"] = stripped.stat().st_size
                if stripped_elf["dynamic_symbols"] != row["elf"]["dynamic_symbols"]:
                    raise ValueError("Pinned strip probe changed runtime dynamic symbol count")
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                errors.append(entry.filename + ": " + str(error))
    if not libraries:
        errors.append("APK contains no native libraries")
    if abis != set(required_abis):
        errors.append("APK ABI set differs: expected " + str(sorted(required_abis)) + ", got " + str(sorted(abis)))
    return {"path": str(apk_path), "sha256": sha256(apk_path), "apk_bytes": apk_path.stat().st_size,
            "apk_MB": apk_path.stat().st_size / 1_000_000, "apk_MiB": apk_path.stat().st_size / 1_048_576,
            "abis": sorted(abis), "libraries": libraries,
            "native_bytes": sum(row["bytes"] for row in libraries),
            "native_zip_bytes": sum(row["zip_bytes"] for row in libraries),
            "bytes_removed": sum(row.get("bytes_removed", 0) for row in libraries), "errors": errors}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--merged-libs", type=Path)
    parser.add_argument("--require-abi", action="append", choices=ABI_ELF, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    # A report must never replace an APK, merged input or toolchain file.
    if (args.output.resolve() == args.apk.resolve() or
            args.output.resolve().is_relative_to(args.ndk.resolve()) or
            (args.merged_libs and args.output.resolve().is_relative_to(args.merged_libs.resolve()))):
        print("verify-native: output must not overwrite inputs", file=sys.stderr)
        return 2
    report = {"schema_version": 1, "units": "bytes; MB=1000000 bytes; MiB=1048576 bytes", "success": False}
    try:
        tool, report["toolchain"] = strip_tool(args.ndk)
        report["apk"] = inspect_apk(args.apk, tool, args.merged_libs, args.require_abi)
        report["errors"] = report["apk"]["errors"]
        report["success"] = not report["errors"]
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        report["errors"] = [str(error)]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    for error in report["errors"]:
        print("verify-native: " + error, file=sys.stderr)
    return 0 if report["success"] else 1


if __name__ == "__main__":
    sys.exit(main())
