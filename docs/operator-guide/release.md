# Prepare a release

Use a clean checkout, JDK 21, Docker for the integration tests, Helm 3, npm 10 or newer and Python 3. Run `scripts/package-release.sh`. It verifies the Maven reactor, checks the chart version and writes the following files under `target/release/<version>/`.

| Artifact | Contents |
| --- | --- |
| `platform-server-<version>.jar`, `platform-worker-<version>.jar` | Executable Spring Boot applications |
| `agent-platform-<version>.tgz` | Helm chart for the server, worker and Studio |
| `sdk-maven-repository.zip` | Parent, BOM, managed SDK JARs, POMs and SHA-256 checksums in Maven repository layout |
| `maven-sbom.json`, `studio-sbom.json` | CycloneDX component inventories for Java and Studio's locked production npm graph |
| `SHA256SUMS` | Release artifact digests |

Validate downloaded artifacts with `shasum -a 256 -c SHA256SUMS` in the release directory. To consume the SDK, extract its ZIP into a file or internal HTTPS Maven repository and import `io.agentplatform:platform-bom:<version>`. Maven must also be able to resolve the upstream Spring Boot and Spring AI BOMs and normal third-party dependencies through your configured mirror.

Review dependency licenses, vulnerabilities and base-image inventories before redistribution. The Java and Studio inventories describe application dependencies. Generate a container SBOM from each built image to include OS packages. Build the Studio image from the matching source revision and use the same immutable version for all three images and the chart.

Registry publication requires a verified namespace, a publication identity, signing keys and registry access. The release owner signs the manifest and images, publishes the reviewed artifacts, then checks the published digests and an installation from the release.

For local release qualification install Trivy and GnuPG. Refresh Trivy's vulnerability databases from an approved source, then run `scripts/qualify-release.sh target/release/<version> <full-gpg-fingerprint>`. The script verifies the files, scans both CycloneDX inventories against the local database, stops on high or critical findings, and signs the checksum manifest with the selected identity. Distribute the verification key through a separate trusted channel. Container inventory and image signing belong to the build that produces the final image digest. [Trivy SBOM scanning](https://trivy.dev/docs/latest/target/sbom/)
