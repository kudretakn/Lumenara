"""Run without Gradle: python3 -B -m unittest discover -s app/src/test/python -p 'Wp10*Test.py'."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import warnings
import zipfile


ROOT = Path(__file__).resolve().parents[4]
SPEC = importlib.util.spec_from_file_location("wp10_native", ROOT / "scripts/verify-native.py")
NATIVE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(NATIVE)


def elf(bits=64, machine=62, debug=False, endian="<", symbols=2):
    """Small ELF fixture with real section headers and both kinds of symbols."""
    symbol_stride = 24 if bits == 64 else 16
    names = b"\0.text\0.dynsym\0.dynstr\0.shstrtab\0.debug_info\0.symtab\0.strtab\0"
    sections = [(".text", 1, b"CODE", 0), (".dynsym", 11, b"\0" * (symbol_stride * symbols), symbol_stride),
                (".dynstr", 3, b"\0JNI_OnLoad\0", 0), (".shstrtab", 3, names, 0)]
    if debug:
        sections += [(".debug_info", 1, b"DEBUG" * 25, 0),
                     (".symtab", 2, b"\0" * (symbol_stride * 3), symbol_stride),
                     (".strtab", 3, b"\0private_function\0", 0)]
    size = 64 if bits == 64 else 52
    data = bytearray(size)
    headers = [(0,) * 10]
    for name, kind, content, stride in sections:
        headers.append((names.index(name.encode()), kind, 0, 0, len(data), len(content), 0, 0, 1, stride))
        data.extend(content)
    shoff = len(data)
    shformat = "IIQQQQIIQQ" if bits == 64 else "IIIIIIIIII"
    for header in headers:
        data.extend(struct.pack(endian + shformat, *header))
    data[:16] = b"\x7fELF" + bytes([2 if bits == 64 else 1, 1 if endian == "<" else 2, 1]) + b"\0" * 9
    struct.pack_into(endian + ("HHIQQQIHHHHHH" if bits == 64 else "HHIIIIIHHHHHH"), data, 16,
                     3, machine, 1, 0, 0, shoff, 0, size, 0, 0,
                     struct.calcsize(endian + shformat), len(headers), 4)
    return bytes(data)


class Wp10NativeTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix="Wp10 with spaces ")
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.ndk = self.directory / "ndk"
        self.ndk.mkdir()
        (self.ndk / "source.properties").write_text("Pkg.Revision = " + NATIVE.NDK_VERSION + "\n")
        self.tool = self.ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
        self.tool.parent.mkdir(parents=True)
        # A tool fixture, not a compiler or the real NDK. Real strip needs lead CI.
        self.tool.write_text("#!" + sys.executable + "\nimport shutil, sys\n"
                             "if '--version' in sys.argv: print('WP10 fixture strip')\n"
                             "else: shutil.copyfile(sys.argv[-1], sys.argv[sys.argv.index('-o') + 1])\n")
        self.tool.chmod(0o755)
        self.output = self.directory / "report.json"

    def apk(self, entries=None):
        path = self.directory / "candidate.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"manifest")
            archive.writestr("classes.dex", b"dex")
            for name, data in (entries if entries is not None else {"lib/x86_64/libfixture.so": elf()}).items():
                archive.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
        return path

    def cli(self, apk, *extra):
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            code = NATIVE.main([str(apk), "--ndk", str(self.ndk), "--require-abi", "x86_64",
                                "--output", str(self.output), *map(str, extra)])
        report = json.loads(self.output.read_text()) if self.output.exists() else None
        return code, report, stderr.getvalue()

    def test_elf32_elf64_both_endiannesses_and_dynamic_symbols_are_not_debug(self):
        for bits, machine in ((32, 40), (64, 183)):
            for endian in ("<", ">"):
                with self.subTest(bits=bits, endian=endian):
                    report = NATIVE.inspect_elf(elf(bits, machine, endian=endian))
                    self.assertEqual((bits, machine), (report["bits"], report["machine"]))
                    self.assertEqual(0, report["debug_static_symbol_bytes"])
                    self.assertEqual(2, report["dynamic_symbols"])

    def test_exact_sizes_hashes_merged_before_after_and_input_files_unchanged(self):
        apk = self.apk()
        before = apk.read_bytes()
        merged = self.directory / "merged/x86_64/libfixture.so"
        merged.parent.mkdir(parents=True)
        merged.write_bytes(elf(debug=True))
        code, report, stderr = self.cli(apk, "--merged-libs", merged.parent.parent)
        self.assertEqual(0, code, stderr)
        self.assertTrue(report["success"])
        self.assertEqual(NATIVE.NDK_VERSION, report["toolchain"]["ndk_version"])
        self.assertEqual(NATIVE.sha256(self.tool), report["toolchain"]["sha256"])
        library = report["apk"]["libraries"][0]
        self.assertEqual(len(elf()), library["bytes"])
        self.assertEqual(len(elf(debug=True)) - len(elf()), library["bytes_removed"])
        self.assertGreater(library["before_strip"]["elf"]["debug_static_symbol_bytes"], 0)
        self.assertEqual(NATIVE.sha256(apk), report["apk"]["sha256"])
        self.assertEqual(len(before), report["apk"]["apk_bytes"])
        self.assertLess(library["zip_bytes"], library["bytes"])
        self.assertEqual(before, apk.read_bytes())
        self.assertEqual(elf(debug=True), merged.read_bytes())

    def test_unstripped_apk_fails_and_still_writes_evidence(self):
        code, report, _ = self.cli(self.apk({"lib/x86_64/libfixture.so": elf(debug=True)}))
        self.assertEqual(1, code)
        self.assertFalse(report["success"])
        self.assertIn("debug/static symbol payload", str(report["errors"]))

    def test_wrong_missing_abi_bad_elf_and_no_native_are_rejected(self):
        for entries in ({}, {"lib/x86_64/a.so": b"bad ELF"},
                        {"lib/x86_64/a.so": elf(machine=183)},
                        {"lib/x86/a.so": elf(32, 3)}):
            with self.subTest(entries=list(entries)):
                code, report, _ = self.cli(self.apk(entries))
                self.assertEqual(1, code)
                self.assertTrue(report["errors"])

    def test_dynamic_symbol_loss_or_missing_merged_input_is_rejected(self):
        merged = self.directory / "merged/x86_64/libfixture.so"
        merged.parent.mkdir(parents=True)
        apk = self.apk()
        self.assertEqual(1, self.cli(apk, "--merged-libs", merged.parent.parent)[0])
        merged.write_bytes(elf(symbols=3))
        code, report, _ = self.cli(apk, "--merged-libs", merged.parent.parent)
        self.assertEqual(1, code)
        self.assertIn("dynamic symbol count", str(report["errors"]))

    def test_ndk_version_missing_tool_and_failed_tool_are_rejected(self):
        apk = self.apk()
        properties = self.ndk / "source.properties"
        properties.write_text("Pkg.Revision = 99.0.0\n")
        self.assertEqual(1, self.cli(apk)[0])
        properties.write_text("Pkg.Revision = " + NATIVE.NDK_VERSION + "\n")
        self.tool.unlink()
        self.assertEqual(1, self.cli(apk)[0])
        self.tool.write_text("#!/bin/sh\nexit 1\n")
        self.tool.chmod(0o755)
        self.assertEqual(1, self.cli(apk)[0])

    def test_strip_probe_failure_after_version_check_stays_red(self):
        self.tool.write_text("#!/bin/sh\nif [ \"$1\" = --version ]; then echo fixture; else exit 7; fi\n")
        self.tool.chmod(0o755)
        code, report, _ = self.cli(self.apk())
        self.assertEqual(1, code)
        self.assertFalse(report["success"])
        self.assertIn("exit status 7", str(report["errors"]))

    def test_corrupt_truncated_and_duplicate_archives_fail(self):
        for data in (elf()[:12], elf()[:60], elf()[:-1]):
            with self.subTest(length=len(data)), self.assertRaises(ValueError):
                NATIVE.inspect_elf(data)
        apk = self.apk()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(apk, "a") as archive:
                archive.writestr("lib/x86_64/libfixture.so", elf())
        self.assertEqual(1, self.cli(apk)[0])
        apk.write_bytes(b"broken ZIP")
        self.assertEqual(1, self.cli(apk)[0])

    def test_output_cannot_overwrite_apk_or_ndk(self):
        apk = self.apk()
        original = apk.read_bytes()
        for path in (apk, self.tool):
            with self.subTest(path=path), contextlib.redirect_stderr(io.StringIO()):
                code = NATIVE.main([str(apk), "--ndk", str(self.ndk), "--require-abi", "x86_64",
                                    "--output", str(path)])
                self.assertEqual(2, code)
        self.assertEqual(original, apk.read_bytes())


if __name__ == "__main__":
    unittest.main()
