# Changelog

All notable changes are documented here. This project follows Semantic Versioning while recognizing that `0.x` releases are public previews whose APIs may change between minor versions.

## [Unreleased]

## [0.2.0] - 2026-09-12

### Added

- Provider-neutral AgentScope `ThinkingBlock` event capture for ordered Chat Span reasoning, text, and tool-call parts.
- Independent `CLS_REASONING_CAPTURE` privacy control, defaulting to `off` without reasoning plaintext or stable Hash.
- Bounded Reasoning metrics for presence, block count, bytes, duration, reasoning/response time to first token, and truncation; the compatibility-named malformed-event field covers Thinking, Text, and Tool Call output block lifecycles.
- Explicit Thinking controls in the travel Demo and Provider-neutral end-to-end integration coverage.
- Bounded message conversion and streamed output accumulation with fixed memory/part limits; final budgets preserve the latest final answer and Tool identity first.
- Incomplete message conversion no longer publishes a misleading input Hash.

### Changed

- Misleading zero-valued reasoning token attributes are omitted when upstream usage is unavailable.

### Verified

- Live DeepSeek `deepseek-chat` Thinking enabled: ordered reasoning/text Chat parts, privacy isolation, `off` semantics, and clean CLS delivery counters.

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
