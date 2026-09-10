#!/usr/bin/env python3
"""Fail when a CycloneDX SBOM component has a known OSV vulnerability."""

from __future__ import annotations

import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

OSV_QUERY_BATCH = "https://api.osv.dev/v1/querybatch"
MAX_ATTEMPTS = 3


def load_components(path: pathlib.Path) -> list[tuple[str, str]]:
    document = json.loads(path.read_text(encoding="utf-8"))
    components: list[tuple[str, str]] = []
    for component in document.get("components", []):
        group = component.get("group")
        name = component.get("name")
        version = component.get("version")
        if group and name and version:
            components.append((f"{group}:{name}", version))
    if not components:
        raise ValueError(f"no Maven components found in {path}")
    return components


def query_osv(components: list[tuple[str, str]]) -> dict:
    body = json.dumps(
        {
            "queries": [
                {
                    "package": {"ecosystem": "Maven", "name": name},
                    "version": version,
                }
                for name, version in components
            ]
        }
    ).encode("utf-8")
    request = urllib.request.Request(
        OSV_QUERY_BATCH,
        data=body,
        headers={"Content-Type": "application/json", "User-Agent": "agentscope-cls-ci/0.1"},
        method="POST",
    )
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError):
            if attempt == MAX_ATTEMPTS:
                raise
            time.sleep(attempt * 2)
    raise AssertionError("unreachable")


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: verify_osv_sbom.py <cyclonedx-bom.json>", file=sys.stderr)
        return 2
    components = load_components(pathlib.Path(sys.argv[1]))
    results = query_osv(components).get("results", [])
    if len(results) != len(components):
        raise ValueError("OSV response size does not match the SBOM component count")

    findings: list[tuple[str, str, str]] = []
    for (name, version), result in zip(components, results, strict=True):
        for vulnerability in result.get("vulns", []):
            findings.append((name, version, vulnerability["id"]))

    print(f"OSV scanned {len(components)} Maven components; findings={len(findings)}")
    for name, version, vulnerability_id in findings:
        print(f"{vulnerability_id}: {name}:{version}", file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())
