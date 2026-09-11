# Changelog

All notable changes are documented here. This project follows Semantic Versioning while recognizing that `0.x` releases are public previews whose APIs may change between minor versions.

## [Unreleased]

- Added provider-neutral AgentScope `ThinkingBlock` event capture for ordered Chat Span reasoning, text, and tool-call parts.
- Added independent `CLS_REASONING_CAPTURE` privacy control, defaulting to `off` without reasoning plaintext or stable Hash.
- Added bounded Reasoning metrics for presence, block count, bytes, duration, reasoning/response time to first token, truncation, and malformed events.
- Removed misleading zero-valued reasoning token attributes when upstream usage is unavailable.
- Added explicit Thinking controls to the travel Demo and Provider-neutral end-to-end integration coverage.

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
