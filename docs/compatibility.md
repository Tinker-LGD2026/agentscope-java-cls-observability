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

通用 Reasoning 契约基于 AgentScope Core 2.0.3 的 `ThinkingBlock` 和 Thinking Start/Delta/End events。验证分两层：

- 自动化：Provider 无关的测试 Model 经完整 ReAct 事件链生成 Chat Reasoning parts（单元与集成测试）；
- 真实联调：DeepSeek `deepseek-chat` 开启 Thinking（`thinking: {"type":"enabled"}`），通过 AgentScope OpenAI 兼容扩展 + `DeepSeekFormatter` 端到端写入 CLS 验证通过——reasoning/text 顺序、隐私隔离（无 signature/encrypted metadata 泄漏）、`truncate` 与 `off` 语义、投递计数（acceptedSpans=44、invalidSpans=0、exportFailures=0）均符合设计。

OpenAI-compatible、Anthropic、Gemini 等模型是否产生完整 Thinking 事件，取决于对应 AgentScope Model Extension 对原始协议的适配。SDK 不直接解析 Provider 字段，也不承诺未验证 Extension 的原始协议兼容性；Extension 未产生 `ThinkingBlock` 时，SDK 不会猜测或重建推理内容。

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

## 0.2 → 0.3 迁移

常规接入只需升级版本号：0.2 的公开 API 无删除、无签名变更，0.2 源码与预编译二进制 fixture
均在 0.3 上通过编译与运行。以下行为变化需要知晓：

1. **控制事件语义**：`AllToolsDenied`/`ExceedMaxIters`/`RequestStop` 不再误报普通 `stop`；
   它们决定 `gen_ai.turn.finish_reason`（denied/max_iters/interrupted），Span 标记
   `gen_ai.incomplete=true`，`completed` 取决于终止前是否观察到 AgentResult；
2. **Provider payload 独立开关**：`CLS_PROVIDER_PAYLOAD_CAPTURE` 默认 `off`，0.2 不受影响；
3. **Attribute 上限收紧**：单 field 上限从 1.1 MB 配置值收紧为不超过 1,000,000 UTF-8 bytes；
   0.2 更大的配置值启动时告警并钳制，不拒绝启动；
4. **Tool 局部失败**：父层 Span 增加 `gen_ai.partial_failure` 与 `gen_ai.failed_tool_count`
   指标，局部失败不再把整轮标记为 error；
5. **Host Link**：宿主已有 OTel Span 时 CLS Entry 保持独立 Trace 并写入 Link；可用
   `CLS_HOST_TRACE_LINK_ENABLED=false` 关闭；
6. **Reactor Hook 迁移**：`CLS_REACTOR_CONTEXT_HOOK=false` 映射 `private`（默认），`true`
   映射 `legacy_hook` 并打印弃用告警；新接入使用 `CLS_REACTOR_CONTEXT_MODE`；
7. **关闭语义**：`flush()` 单飞合并；新增 `shutdown(Duration)` 优雅关闭；`close()` 等价于
   `shutdown(配置的 shutdownTimeout)`；DRAINING 后新根调用不产生遥测、业务照常；
8. **指标**：新增 `detailedSnapshot()` 细分计数；旧 `snapshot()` 的 `exportFailures` 现在
   聚合导出、flush 与 shutdown 失败，`droppedSpans` 包含队列/内存溢出。

## CI 策略

每个 Pull Request：

```text
JDK 17 → ./mvnw clean verify
JDK 21 → ./mvnw clean verify
```

只有矩阵持续通过的版本才会写入“已验证”列表。
