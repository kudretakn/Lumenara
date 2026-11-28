"""Validate lead-generated Gradle dependency evidence; never synthesize lock/checksum data."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


MODULES = ("app", "core-catalog", "core-data", "core-designsystem", "core-player-api",
           "core-player", "core-player-vlc", "baselineprofile")


def check(root):
    root = Path(root)
    files = [root / "gradle/libs.versions.toml", root / "gradle/verification-metadata.xml"]
    files += [root / "gradle/wp20-locks" / (module + ".lockfile") for module in MODULES]
    for path in files:
        if not path.is_file() or not path.stat().st_size:
            raise ValueError("Missing WP20 dependency evidence: " + str(path.relative_to(root)) +
                             "; lead must follow gradle/Wp20Build.md before CI")
    metadata = ET.parse(files[1]).getroot()
    namespace = metadata.tag.split("}")[0] + "}" if "}" in metadata.tag else ""
    config = metadata.find(namespace + "configuration")
    if config is None or config.findtext(namespace + "verify-metadata") != "true":
        raise ValueError("WP20 requires verification of dependency metadata as well as artifacts")
    for name in ("trusted-artifacts", "ignored-keys"):
        node = config.find(namespace + name)
        if node is not None and len(node):
            raise ValueError("WP20 does not permit blanket verification exemptions: " + name)
    artifacts = metadata.findall(".//" + namespace + "artifact")
    if not artifacts:
        raise ValueError("WP20 verification metadata contains no resolved artifacts")
    for artifact in artifacts:
        checksums = artifact.findall(namespace + "sha256")
        if not checksums or any(not re.fullmatch(r"[a-fA-F0-9]{64}", c.get("value", "")) for c in checksums):
            raise ValueError("WP20 requires SHA-256 for every artifact: " + artifact.get("name", "?"))
    for path in files[2:]:
        lines = [line for line in path.read_text().splitlines() if line and not line.startswith("#")]
        if not any(re.match(r"[^:=]+:[^:=]+:[^=]+=.+", line) for line in lines):
            raise ValueError("WP20 lock has no resolved coordinates/configurations: " + path.name)
        if any("+" in line.split("=", 1)[0] or "SNAPSHOT" in line for line in lines):
            raise ValueError("WP20 lock contains a changing/dynamic version: " + path.name)
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    try:
        result = check(args.root)
    except (OSError, ValueError, ET.ParseError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
