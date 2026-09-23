#!/usr/bin/env python3
"""Release metadata gate: tag format, tag/version/notes consistency, main ancestry.

Usage (from the repository root, with full history fetched):

    python3 scripts/verify_release_metadata.py --tag v0.3.0 [--main origin/main]

Exits 0 when all checks pass; exits 1 with one diagnostic per failed check.
All subprocess invocations use argv lists; nothing is interpolated into a shell.
"""

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

TAG_PATTERN = re.compile(r"^v(\d+\.\d+\.\d+)$")


def fail(errors: list[str], message: str) -> None:
    errors.append(message)


def git(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", *args],
        capture_output=True,
        text=True,
    )


def project_version(pom: Path) -> str | None:
    try:
        root = ET.parse(pom).getroot()
    except (OSError, ET.ParseError):
        return None
    namespace = ""
    if root.tag.startswith("{"):
        namespace = root.tag[: root.tag.index("}") + 1]
    version = root.find(f"{namespace}version")
    return None if version is None or not version.text else version.text.strip()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True, help="release tag, e.g. v0.3.0")
    parser.add_argument(
        "--main",
        default="origin/main",
        help="main branch ref the tag must be reachable from (default: origin/main)",
    )
    parser.add_argument(
        "--notes",
        default=".github/release-notes.md",
        help="release notes file (default: .github/release-notes.md)",
    )
    parser.add_argument("--pom", default="pom.xml", help="root pom (default: pom.xml)")
    args = parser.parse_args()

    errors: list[str] = []

    match = TAG_PATTERN.match(args.tag)
    if match is None:
        fail(errors, f"tag {args.tag!r} must match vX.Y.Z")
        version = None
    else:
        version = match.group(1)
        if version.endswith("SNAPSHOT"):
            fail(errors, f"tag {args.tag!r} must not be a SNAPSHOT")

    version_from_pom = project_version(Path(args.pom))
    if version_from_pom is None:
        fail(errors, f"cannot read project version from {args.pom}")
    elif version is not None:
        if "SNAPSHOT" in version_from_pom:
            fail(
                errors,
                f"pom.xml version {version_from_pom!r} is a SNAPSHOT; release requires a final version",
            )
        elif version != version_from_pom:
            fail(
                errors,
                f"tag version {version!r} does not match pom.xml version {version_from_pom!r}",
            )

    tags = git("rev-parse", "--verify", f"refs/tags/{args.tag}^{{commit}}")
    if tags.returncode != 0:
        fail(errors, f"tag {args.tag!r} does not exist")
    else:
        ancestry = git("merge-base", "--is-ancestor", args.tag, args.main)
        if ancestry.returncode != 0:
            fail(errors, f"tag {args.tag!r} is not reachable from {args.main}")

    notes = Path(args.notes)
    if version is not None:
        if not notes.is_file():
            fail(errors, f"release notes {args.notes} not found")
        elif version not in notes.read_text(encoding="utf-8"):
            fail(errors, f"release notes {args.notes} do not mention version {version}")

    if errors:
        for error in errors:
            print(f"release metadata check failed: {error}", file=sys.stderr)
        return 1
    print(f"release metadata OK: {args.tag}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
