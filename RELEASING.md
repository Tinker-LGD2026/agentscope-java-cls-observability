# Releasing

This phase publishes GitHub source and Release assets only. It does not publish Maven Central or GitHub Packages.

## Prerequisites

- Repository owner approved the version and release notes.
- All previously exposed credentials were revoked and rotated.
- Branch protection and GitHub security features are enabled.
- CI passes on JDK 17 and JDK 21.
- OSV scans report zero known vulnerabilities for both the SDK runtime SBOM and the AgentScope 2.0.3 consumer baseline SBOM.
- `CHANGELOG.md` and `.github/release-notes.md` contain the release.
- Full repository and Git history secret scans report no valid secrets.
- The protected GitHub Environment named `release` exists and requires approval.

## Prepare

```bash
./mvnw clean verify
./mvnw -pl agentscope-cls-observability-sdk javadoc:javadoc
```

Confirm the POM version is the exact release version (for example `0.3.0` for tag `v0.3.0`). The release workflow runs `scripts/verify_release_metadata.py`, which rejects a tag that is malformed, whose `v`-stripped value differs from the POM version, that is not reachable from `origin/main`, or whose `.github/release-notes.md` does not mention the version. The workflow builds and verifies on both JDK 17 and JDK 21, runs a full-history Gitleaks scan, OSV scans both SBOMs, and checks all release assets. After publishing, prepare the next development version in a separate change.

## Tag

Only after explicit repository-owner approval (replace `X.Y.Z` with the target version):

```bash
git tag -a vX.Y.Z -m "AgentScope CLS Observability SDK X.Y.Z"
git push origin vX.Y.Z
```

## Expected assets

- `agentscope-cls-observability-sdk-X.Y.Z.jar`
- `agentscope-cls-observability-sdk-X.Y.Z-sources.jar`
- `agentscope-cls-observability-sdk-X.Y.Z-javadoc.jar`
- `bom.json`
- `bom-consumer.json`
- `SHA256SUMS`

The Demo JAR, test reports, internal evidence, environment files, and credentials must not be uploaded.

## Verify

Download all assets in a clean directory:

```bash
sha256sum -c SHA256SUMS
jar tf agentscope-cls-observability-sdk-X.Y.Z.jar
```

Confirm the GitHub Release states that installation is currently source clone plus `./mvnw install`; Maven Central is not yet available.

## Revoke a bad release

- Mark the GitHub Release as a draft or delete it.
- Delete the affected tag only if it has not been consumed; never force-push `main`.
- Rotate any credential that may have entered an asset or log.
- Publish a corrected patch ver