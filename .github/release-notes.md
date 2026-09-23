## AgentScope Java CLS Observability SDK 0.3.0

第三个公开预览版本：可观测性硬化（spec 030）——背压、隐私、Span 语义、导出管线与生命周期全面加固。

### 新增

- **HITL / 外部执行生命周期语义**：有界等待、超时 tombstone、恰好一次的 generation rotation，以及确定性的终止结果（`gen_ai.turn.finish_reason`、`gen_ai.incomplete`、resume 关联）；超时轮换后恢复的业务落在新的 turn/trace 上。
- **独立 Provider 载荷捕获** `CLS_PROVIDER_PAYLOAD_CAPTURE`（默认 `off`），canonical off/hash/truncate/full 包络。
- **字节感知批处理**：`ClsBatchSpanProcessor` 在 span 结束时即编码为有界记录，数量/字节/时间三重触发，队列与内存溢出计入 `droppedSpans`；`encodedSpanQueueBytes`  gauge。
- **CLS 物理批量规划**：`CLS_MAX_EXPORT_BATCH_BYTES`、`CLS_MAX_EXPORT_BATCH_COUNT`、`CLS_PRODUCER_LINGER_MS`、`CLS_MAX_PRODUCER_BUFFER_BYTES`；自定义 `SpanSink` 仍接收处理器批次。
- **优雅停机** `shutdown(Duration)`：DRAINING 语义 + 40/65/85/100 里程碑预算；`close()` 委托且永不抛出遥测故障；卡死调用可被 freeze 收口并释放资源。
- **Reactor 上下文模式** `CLS_REACTOR_CONTEXT_MODE=private|bridge|legacy_hook`：实例级隔离键、重复中间件检测、可选宿主 trace 链接（`CLS_HOST_TRACE_LINK_ENABLED`，默认 `true`）。
- `detailedSnapshot()` 细粒度计数器；`scripts/verify_release_metadata.py` 发布门禁。

### 变更

- 单字段属性预算收紧至 1,000,000 UTF-8 字节（0.2 的更大值会被钳制并告警）。
- 本地工具失败将父 Span 标记为 partial（`gen_ai.partial_failure`、`gen_ai.failed_tool_count`），不再使整个 turn 失败。
- 控制事件不再上报 plain `stop` finish reason。
- `CLS_REACTOR_CONTEXT_HOOK` 弃用，映射到新的模式设置。

### 隐私

- Reasoning、Provider 载荷与正文保持独立捕获开关，默认全部 `off`；流式内容绝不在事后伪造。

### 已验证

- JDK 17 + JDK 21 全量 `clean verify`：343 SDK + 24 demo 测试全绿；
- 真实 DeepSeek/CLS 联调矩阵（off/truncate/full × 正文/推理/Provider 载荷）通过，计数器零异常；
- OSV-Scanner 双 SBOM 零发现；Gitleaks 全历史干净；CI 十项检查（含 CodeQL、部署模板、发布元数据脚本）全绿。
