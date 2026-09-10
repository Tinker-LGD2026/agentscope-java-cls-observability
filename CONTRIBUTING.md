# Contributing

## Development environment

- JDK 17 or 21
- Maven Wrapper included in the repository
- UTF-8 source files

```bash
java -version
./mvnw clean verify
```

## Pull requests

Keep changes focused and add tests before implementation for behavior changes.

Checklist:

- [ ] `./mvnw clean verify` passes
- [ ] JDK 17 and JDK 21 CI pass
- [ ] New behavior has a regression test
- [ ] Public API changes include Javadoc and CHANGELOG entries
- [ ] README and configuration defaults match the code
- [ ] No credentials, customer data, complete prompts, Tool results, Topic IDs, or local absolute paths
- [ ] No cwd/Git metadata collection was added
- [ ] No fabricated `gen_ai.input.messages_delta` was added
- [ ] Content and identity changes include a privacy review

## Style

- Follow the existing Java formatting.
- Prefer small, focused classes.
- Keep telemetry failures isolated from Agent business behavior.
- Do not log business content or credentials in exceptions.
- Keep all accumulators and external responses bounded.
- Do not register global OpenTelemetry or Reactor state by default.

## Testing

Run focused tests while developing, then the complete suite:

```bash
./mvnw -pl agentscope-cls-observability-sdk test
./mvnw -pl demo test
./mvnw clean verify
```

Real cloud tests must not run on untrusted pull requests. Use protected GitHub Environments and short-lived credentials for manual integration verification.

By contributing, you agree that your contribution is licensed under Apache License 2.0.
