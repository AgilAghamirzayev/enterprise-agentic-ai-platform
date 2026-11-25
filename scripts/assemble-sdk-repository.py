#!/usr/bin/env python3
"""Pack this project's SDK and Maven metadata into a versioned file repository."""
import hashlib
from pathlib import Path
import sys
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo
import xml.etree.ElementTree as ET


def assemble(version, archive):
    root = Path(__file__).resolve().parents[1]
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    bom = ET.parse(root / "platform-bom/pom.xml")
    sdk = [node.text for node in bom.findall("m:dependencyManagement/m:dependencies/m:dependency/m:artifactId", namespace)]
    with ZipFile(archive, "w", compression=ZIP_DEFLATED) as output:
        for name in ["platform-parent", "platform-bom", *sdk]:
            module = root if name == "platform-parent" else root / name
            files = {"pom": module / "pom.xml"}
            if name in sdk:
                files["jar"] = module / "target" / f"{name}-{version}.jar"
            for suffix, path in files.items():
                data = path.read_bytes()
                location = f"io/agentplatform/{name}/{version}/{name}-{version}.{suffix}"
                checksums = {
                    ".sha1": hashlib.sha1(data, usedforsecurity=False).hexdigest(),
                    ".sha256": hashlib.sha256(data).hexdigest(),
                }
                entries = [(location, data), *((location + suffix, value.encode()) for suffix, value in checksums.items())]
                for filename, contents in entries:
                    entry = ZipInfo(filename)
                    entry.compress_type = ZIP_DEFLATED
                    output.writestr(entry, contents)


if __name__ == "__main__":
    assemble(sys.argv[1], sys.argv[2])
