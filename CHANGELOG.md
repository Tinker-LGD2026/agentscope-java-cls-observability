# Changelog

All notable changes are documented here. This project follows Semantic Versioning while recognizing that `0.x` releases are public previews whose APIs may change between minor versions.

## [Unreleased]

- Prepare the public GitHub repository, documentation, CI, security checks, and release workflow.

## [0.1.0] - 2026-09-10

### Added

- AgentScope Java 2.0.3 Middleware integration.
- Entry, Agent, Step, Chat, and Tool Span topology.
- Session, Turn, User, model, provider, Token, Tool, status, and duration fields.
- Console and Tencent CLS Cloud transports.
- `off`, `hash`, `truncate`, and `full` content modes with bounded capture and redaction.
- Asynchronous batching, schema validation, telemetry counters, flush, and shutdown handling.
- UTF-8 byte budgets with stable fingerprints for oversized Session, User, Turn, and display identity fields.
- Native AgentScope SubAgentTool tracing.
- Offline deterministic Demo.
- Real DeepSeek, Open-Meteo, multi-Agent travel Demo.
- Local, CVM, Docker, and TKE deployment guidance.

### Known limitations

- Only AgentScope Java 2.0.3 is verified.
- `gen_ai.input.messages_delta` is not generated.
- cwd and Git metadata are not collected.
- `off` mode still emits a stable SHA-256 fingerprint for Chat input messages.
- The in-memory export queue is not suitable as an audit or billing source.
