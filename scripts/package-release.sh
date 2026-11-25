#!/bin/sh
set -eu

# Build locally so maintainers can inspect artifacts before publication.
cd "$(dirname "$0")/.."
command -v npm >/dev/null || { echo "npm 10 or newer is required for the Studio SBOM" >&2; exit 1; }
command -v python3 >/dev/null || { echo "Python 3 is required for the SDK archive" >&2; exit 1; }
command -v helm >/dev/null || { echo "Helm 3 is required" >&2; exit 1; }

./mvnw -B -ntp verify org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom
version=$(./mvnw -q -N help:evaluate -Dexpression=project.version -DforceStdout)
chart=deployments/helm/agent-platform
chart_version=$(awk '$1 == "version:" {print $2}' "$chart/Chart.yaml")
if [ "$chart_version" != "$version" ]; then
  echo "Chart and Maven versions must match" >&2
  exit 1
fi

out=target/release/$version
mkdir -p "$out"
helm lint --strict "$chart"
helm package "$chart" --destination "$out"
cp target/bom.json "$out/maven-sbom.json"
for module in platform-server platform-worker; do
  cp "$module/target/$module-$version.jar" "$out/"
done
python3 scripts/assemble-sdk-repository.py "$version" "$out/sdk-maven-repository.zip"
(cd platform-studio && npm sbom --offline --package-lock-only --omit=dev --sbom-format=cyclonedx) > "$out/studio-sbom.json"
python3 - "$out" <<'PYTHON'
from hashlib import sha256
from pathlib import Path
import sys

directory = Path(sys.argv[1])
version = directory.name
names = ["maven-sbom.json", "studio-sbom.json", "sdk-maven-repository.zip", f"agent-platform-{version}.tgz",
         f"platform-server-{version}.jar", f"platform-worker-{version}.jar"]
files = sorted(directory / name for name in names)
def digest(path):
    with path.open("rb") as stream:
        value = sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
        return value.hexdigest()

(directory / "SHA256SUMS").write_text("".join(f"{digest(path)}  {path.name}\n" for path in files))
PYTHON
echo "Release artifacts are in $out"
