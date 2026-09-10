# 兼容性

## Java

| JDK | 状态 | 说明 |
|---|---|---|
| 8 | 不支持 | 低于 AgentScope Java 2.0 要求 |
| 11 | 不支持 | 无法运行 Java 17 字节码 |
| 17 | 最低版本、已验证 | SDK 使用 `--release 17` |
| 21 | 已验证 | LTS；仍生成 Java 17 字节码 |
| 其他版本 | 未承诺 | 只有加入 CI 后才列为支持 |

AgentScope Java 2.0 官方要求 JDK 17+。即使 SDK 自己改写为 Java 11，也无法与 AgentScope Java 2.0.3 一起在 JDK 11 运行。

## AgentScope Java

| 版本 | 状态 |
|---|---|
| 2.0.3 | 已验证 |
| 其他 2.x | 未承诺二进制或行为兼容 |
| 1.x | 不支持 |

SDK直接使用 `MiddlewareBase`、`RuntimeContext`、事件类型和原生 `SubAgentTool`。AgentScope 升级后应运行完整测试和真实多 Agent 验证。

## 构建工具

- Maven Wrapper：推荐入口；
- Maven：3.9+；
- 编译目标：Java 17；
- 文本编码：UTF-8。

## 主要依赖基线

| 依赖 | 版本 |
|---|---:|
| AgentScope Core | 2.0.3，`provided` |
| OpenTelemetry SDK | 1.62.0 |
| OpenTelemetry Reactor instrumentation | 2.28.0-alpha |
| Jackson | 2.21.5 |
| Tencent CLS Java SDK | 1.0.17 |
| lz4-java maintained fork | 1.11.1 |
| SLF4J API | 2.0.17 |

`agentscope-core` 是 `provided`，客户应用必须显式提供 AgentScope 运行时。公开版本不承诺任意版本替换都兼容。

本 SDK 和公开 Demo 不使用 MCP，因此 Maven 示例排除了 AgentScope 2.0.3 传递的 `io.modelcontextprotocol.sdk:mcp`。如果客户应用需要 AgentScope MCP 功能，不应照抄该排除项；请先根据 MCP Java SDK 安全公告选择修复版本，并验证其与当前 AgentScope 版本的兼容性。

## CI 策略

每个 Pull Request：

```text
JDK 17 → ./mvnw clean verify
JDK 21 → ./mvnw clean verify
```

只有矩阵持续通过的版本才会写入“已验证”列表。
