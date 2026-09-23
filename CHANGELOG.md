# Changelog

All notable changes are documented here. This project follows Semantic Versioning while recognizing that `0.x` releases are public previews whose APIs may change between minor versions.

## [Unreleased]

## [0.3.0-SNAPSHOT]

### Added

- HITL and external-execution lifecycle semantics: bounded waits, timeout tombstones,
  exactly-once generation rotation, and deterministic terminal outcomes
  (`gen_ai.turn.finish_reason`, `gen_ai.incomplete`, resume linkage).
- Independent provider payload capture (`CLS_PROVIDER_PAYLOAD_CAPTURE`, default `off`) with
  canonical off/hash/truncate/full envelopes.
- Byte-aware `ClsBatchSpanProcessor`: spans are encoded at end time into bounded records;
  count/byte/time triggers; queue and memory overflows count into `droppedSpans`.
- Tencent CLS physical batch planning (`CLS_MAX_EXPORT_BATCH_BYTES`,
  `CLS_MAX_EXPORT_BATCH_COUNT`, `CLS_PRODUCER_LINGER_MS`,
  `CLS_MAX_PRODUCER_BUFFER_BYTES`); custom `SpanSink` still receives processor batches.
- Graceful `shutdown(Duration)` with DRAINING semantics and 40/65/85/100 milestone budgets;
  `close()` delegates to it and never throws telemetry failures.
- Reactor context modes (`CLS_REACTOR_CONTEXT_MODE=private|bridge|legacy_hook`), per-instance
  isolation keys, duplicate middleware detection, and optional host trace links
  (`CLS_HOST_TRACE_LINK_ENABLED`, default `true`).
- `detailedSnapshot()` with per-category counters.
- `scripts/verify_release_metadata.py` release gate.

### Changed

- Single-field attribute budget tightened to 1,000,000 UTF-8 bytes; larger 0.2 values are
  clamped with a startup warning.
- Local tool failures mark the parent span partial (`gen_ai.partial_failure`,
  `gen_ai.failed_tool_count`) instead of failing the whole turn.
- Control events no longer report a plain `stop` finish reason.
- `CLS_REACTOR_CONTEXT_HOOK` is deprecated and maps onto the new mode setting.

### Privacy

- Reasoning, provider payloads, and content remain independent capture switches, all default
  `off`; streamed content is never fabricated after the fact.

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
