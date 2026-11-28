"""WP20 checks use fixture resolutions; only the lead/Gradle can create real lock state."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[4]
SPEC = importlib.util.spec_from_file_location("wp20_dependencies", ROOT / "gradle/Wp20DependencyEvidence.py")
DEPS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPS)


class Wp20DependencyEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="Wp20 dependencies ")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "gradle/wp20-locks").mkdir(parents=True)
        (self.root / "gradle/libs.versions.toml").write_text('[versions]\nfixture = "1.0"\n')
        for module in DEPS.MODULES:
            (self.root / "gradle/wp20-locks" / (module + ".lockfile")).write_text(
                "# Generated fixture, never a repository lock\nexample:fixture:1.0=runtimeClasspath\nempty=\n")
        self.metadata = self.root / "gradle/verification-metadata.xml"
        self.metadata.write_text('<verification-metadata xmlns="https://schema.gradle.org/dependency-verification">'
                                 '<configuration><verify-metadata>true</verify-metadata></configuration>'
                                 '<components><component group="example" name="fixture" version="1.0">'
                                 '<artifact name="fixture-1.0.jar"><sha256 value="' + "a" * 64 + '"/></artifact>'
                                 '</component></components></verification-metadata>')

    def test_complete_evidence_reports_deterministic_file_digests(self):
        result = DEPS.check(self.root)
        self.assertEqual(10, len(result))
        self.assertEqual(result, DEPS.check(self.root))
        catalog = self.root / "gradle/libs.versions.toml"
        catalog.write_text(catalog.read_text() + "# reviewed change\n")
        changed = DEPS.check(self.root)
        self.assertNotEqual(result["gradle/libs.versions.toml"], changed["gradle/libs.versions.toml"])

    def test_every_module_lock_is_required_including_profile_tooling(self):
        for module in DEPS.MODULES:
            path = self.root / "gradle/wp20-locks" / (module + ".lockfile")
            data = path.read_text()
            path.unlink()
            with self.subTest(module=module), self.assertRaisesRegex(ValueError, "Missing WP20"):
                DEPS.check(self.root)
            path.write_text(data)

    def test_empty_or_dynamic_lock_is_rejected(self):
        path = self.root / "gradle/wp20-locks/app.lockfile"
        for text in ("# placeholder\nempty=\n", "example:fixture:1.+=runtimeClasspath\n", "example:fixture:1.0-SNAPSHOT=runtimeClasspath\n"):
            path.write_text(text)
            with self.subTest(text=text), self.assertRaises(ValueError):
                DEPS.check(self.root)

    def test_no_verification_or_bad_digest_cannot_pass(self):
        original = self.metadata.read_text()
        for text in (original.replace("<verify-metadata>true", "<verify-metadata>false"),
                     original.replace('value="' + "a" * 64, 'value="bad'),
                     original.replace('sha256', 'sha1'),
                     original.replace('<components>', '<components><!--'),
                     '<verification-metadata><configuration><verify-metadata>true</verify-metadata>'
                     '</configuration><components/></verification-metadata>'):
            self.metadata.write_text(text)
            with self.subTest(text=text[:40]), self.assertRaises((ValueError, DEPS.ET.ParseError)):
                DEPS.check(self.root)

    def test_trust_exemptions_are_not_accepted(self):
        self.metadata.write_text(self.metadata.read_text().replace('</configuration>',
            '<trusted-artifacts><trust group=".*" regex="true"/></trusted-artifacts></configuration>'))
        with self.assertRaisesRegex(ValueError, "exemptions"):
            DEPS.check(self.root)


if __name__ == "__main__":
    unittest.main()
