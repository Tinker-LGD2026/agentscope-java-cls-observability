## AgentScope Java CLS Observability SDK 0.3.1

0.3.0 的修复版本：兼容阿里云 ARMS 5.1.x 等基于 OpenTelemetry javaagent 的 APM 探针。

### 修复

- 父上下文改以非 recording 的 `PropagatedSpan` 传递：此前在加载 ARMS AgentScope 埋点探针的
  进程中，放入 `Context` 的活跃父 Span 取出时被探针桥接层无效化，导致同一 Turn 的
  `invoke_agent` / `react round` / `chat` 被拆成多个独立 Trace（parentSpanID 全空）。
  修复后 Span 树在探针共存时保持单一 Trace；普通 JVM 行为不变。
- 排障文档新增"APM 探针共存导致多 Trace"条目与规避指引。

### 已验证

- JDK 17 全量 `clean verify`：345 SDK + 24 demo 测试全绿（含新增的 PropagatedSpan 回归测试）。

