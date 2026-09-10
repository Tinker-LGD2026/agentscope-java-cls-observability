# Security Policy

## Supported versions

The latest `0.1.x` preview release receives security fixes. Versions older than the latest preview are not supported unless a release note states otherwise.

## Reporting a vulnerability

Use GitHub Private Vulnerability Reporting for this repository. Do not open a public issue containing:

- exploit details;
- API keys, tokens, or credentials;
- customer prompts, tool results, or personal data;
- private CLS Topic or account information.

Include the affected version, JDK version, AgentScope version, minimal reproduction, expected impact, and suggested remediation if known. Use synthetic data only.

If a credential may have been exposed, revoke and rotate it immediately. Deleting a file or issue does not invalidate a leaked credential.

## Security boundaries

- This SDK is telemetry, not an audit or billing system.
- The export queue is in memory; crashes and forced termination may lose data.
- Content redaction reduces risk but is not a DLP guarantee.
- `CLS_CONTENT_CAPTURE=off` still emits a stable SHA-256 fingerprint for Chat input messages.
- Customers own access control, data residency, retention, consent, and deletion policies.

See [`docs/security-and-privacy.md`](docs/security-and-privacy.md).
