# Third-Party Notices

This project depends on third-party open-source software. Each dependency remains subject to its own license.

Key direct runtime dependencies:

| Project | Version | License |
|---|---:|---|
| AgentScope Java Core | 2.0.3 | Apache License 2.0 |
| OpenTelemetry SDK | 1.62.0 | Apache License 2.0 |
| OpenTelemetry Reactor Instrumentation | 2.28.0-alpha | Apache License 2.0 |
| Jackson Databind | 2.21.5 | Apache License 2.0 |
| Tencent Cloud CLS Java SDK | 1.0.17 | Apache License 2.0 |
| lz4-java maintained fork | 1.11.1 | Apache License 2.0 |
| SLF4J API | 2.0.17 | MIT License |

The release workflow generates two CycloneDX SBOMs: `bom.json` contains the SDK artifact's resolved runtime dependencies, while `bom-consumer.json` adds the tested provided AgentScope 2.0.3 baseline. Consumers must still review any additional dependencies and version overrides in their final application.

The SDK and public demos do not use AgentScope's optional MCP stack, so their Maven dependencies exclude `io.modelcontextprotocol.sdk:mcp`. Applications that use MCP must manage and verify a secure MCP Java SDK version separately.

AgentScope and Tencent Cloud names and trademarks belong to their respective owners. Their appearance identifies compatibility and integration targets and does not imply endorsement.
