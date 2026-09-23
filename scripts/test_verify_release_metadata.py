#!/usr/bin/env python3
"""Tests for verify_release_metadata.py against synthetic git repositories.

Every invocation uses argv lists; no shell interpolation anywhere.
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "verify_release_metadata.py"

POM_TEMPLATE = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.tinkerlgd2026</groupId>
  <artifactId>agentscope-cls-observability</artifactId>
  <version>{version}</version>
  <packaging>pom</packaging>
</project>
"""

NOTES_TEMPLATE = """## Release {version}

Some notes.
"""


def git(repo: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-C", str(repo), *args],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


class VerifyReleaseMetadataTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Path(self.tmp.name)
        git(self.repo, "init", "--initial-branch=main")
        git(self.repo, "config", "user.email", "test@example.com")
        git(self.repo, "config", "user.name", "test")
        self._write("pom.xml", POM_TEMPLATE.format(version="0.3.0"))
        self._write(".github/release-notes.md", NOTES_TEMPLATE.format(version="0.3.0"))
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", "initial")

    def tearDown(self) -> None:
        self.tmp.cleanup()

    def _write(self, relative: str, content: str) -> None:
        path = self.repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def _run(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args],
            cwd=self.repo,
            capture_output=True,
            text=True,
        )

    def _tag(self, tag: str) -> None:
        git(self.repo, "tag", tag)

    def test_happy_path_passes(self) -> None:
        self._tag("v0.3.0")
        result = self._run("--tag", "v0.3.0", "--main", "main")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_rejects_malformed_tag(self) -> None:
        self._tag("0.3.0")
        result = self._run("--tag", "0.3.0", "--main", "main")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("vX.Y.Z", result.stderr)

    def test_rejects_version_mismatch(self) -> None:
        self._tag("v0.4.0")
        result = self._run("--tag", "v0.4.0", "--main", "main")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("pom.xml", result.stderr)

    def test_rejects_tag_not_reachable_from_main(self) -> None:
        git(self.repo, "checkout", "-b", "side")
        self._write("side.txt", "side")
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", "side")
        self._tag("v0.3.0")
        git(self.repo, "checkout", "main")
        result = self._run("--tag", "v0.3.0", "--main", "main")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("main", result.stderr)

    def test_rejects_notes_without_version(self) -> None:
        self._write(".github/release-notes.md", NOTES_TEMPLATE.format(version="0.9.9"))
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", "notes")
        self._tag("v0.3.0")
        result = self._run("--tag", "v0.3.0", "--main", "main")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("release-notes", result.stderr)

    def test_rejects_snapshot_version(self) -> None:
        self._write("pom.xml", POM_TEMPLATE.format(version="0.3.0-SNAPSHOT"))
        git(self.repo, "add", ".")
        git(self.repo, "commit", "-m", "snapshot")
        self._tag("v0.3.0-SNAPSHOT")
        result = self._run("--tag", "v0.3.0-SNAPSHOT", "--main", "main")
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
