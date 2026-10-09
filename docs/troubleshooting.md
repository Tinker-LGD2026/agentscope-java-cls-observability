# 排障指南

## 启动时报 Cloud 配置缺失

Cloud 模式必须同时提供：

```text
CLS_ENDPOINT
CLS_TOPIC_ID
CLS_SECRET_ID
CLS_SECRET_KEY
```

使用临时凭证时还需要 `CLS_SECRET_TOKEN`。

## `topic not found`

检查：

1. `CLS_TOPIC_ID` 是 Trace 日志主题 ID，不是 Agent 可观测应用 ID；
2. Endpoint 地域与 Topic 地域一致；
3. 公网使用 `.cls.tencentcs.com`，腾讯云内网使用 `.cls.tencentyun.com`。

## CLS 看不到数据

1. 确认 `CLS_TRANSPORT=cloud`；
2. 确认凭据对目标 Topic 有写权限；
3. 每次顶层调用传入非空 `sessionId` 和 `userId`；
4. 在短进程退出前调用 `flush()`；
5. 查看 `observability.snapshot()`；
6. 等待 CLS 索引延迟；
7. 在 Agent 可观测应用的调用链页面查看。

## Session 没有聚合

同一连续对话必须传相同 `sessionId`。不要每条消息生成新 Session。`RuntimeContext` 对象可以每次重新创建。

## 相同 Session 为什么有多个 Trace

正常行为：每次顶层 `agent.call()` 是一个新 Turn 和新 Trace，相同 `sessionId` 将多个 Trace 聚合为一段会话。

**异常情形（0.3.0 已修复于 0.3.1）**：如果同一 `gen_ai.turn.id` 下 `entry / invoke_agent / react round / chat`
各自的 `traceID` 不同且 `parentSpanID` 全为空，说明 span 父子链接在创建时被外部改写。
已确认的一个来源是阿里云 ARMS 5.1.x 探针的 AgentScope 2.0 埋点插件：探针的 OTel 桥接层会使
SDK 放入 `Context` 的活跃父 Span 在取出时变成无效值，导致每个 Span 都被重建为新 Trace。
0.3.1 起 SDK 改为以 `PropagatedSpan`（仅含不可变 SpanContext）传递父引用，规避该改写。
若仍使用 0.3.0，规避方式：摘掉探针验证、在探针配置中关闭 AgentScope 插件，或升级探针到
`5.2.0-genai` 专项版本（官方已修复部分 AgentScope 2.0 埋点不兼容问题）。

## 没有生成 Trace

缺少 `sessionId` 或 `userId` 时，SDK 跳过遥测但继续业务。检查 `droppedSpans` 和调用处的 `RuntimeContext`。

## 出现重复 Trace

同一个 Agent 不要同时使用：

- 本 SDK；
- AgentScope `OtelTracingMiddleware`；
- 旧 `TelemetryTracer`；
- 另一套针对同一生命周期的重复 Middleware。

## 看不到输入输出正文

默认 `CLS_CONTENT_CAPTURE=off`。临时排障可以使用：

```bash
export CLS_CONTENT_CAPTURE=truncate
```

开启前阅读 `security-and-privacy.md`，排障后恢复 `off`。

## Token 或模型名缺失

这些数据来自模型服务返回。某些 OpenAI 兼容服务不返回 usage 或响应模型名，SDK 不会凭空推算。

## `flush()` 返回 false

可能原因：

- CLS 网络不可达；
- CAM 权限错误；
- 超时过短；
- SDK 已关闭；
- 同时进行另一次 flush/close；
- Sink 内异步请求失败。

业务结果不应因此改写。记录 `snapshot()`，并检查 `exportFailures`。

## 队列丢弃

可调整：

```bash
export CLS_MAX_QUEUE_SIZE=8192
export CLS_EXPORT_SCHEDULE_DELAY_MS=2000
```

先压测再调整。OTel Processor 内部队列丢弃不计入 SDK `droppedSpans`，同时监控 OTel 日志和指标。

## 容器停止时缺少尾部 Span

- JVM 收到 SIGTERM 后执行 `flush()` 和 `close()`；
- Docker/TKE 给予至少 30 秒优雅终止时间；
- 不使用 `kill -9`；
- 确认应用关闭钩子实际执行。

## Reactor Hook 冲突

默认保持：

```bash
export CLS_REACTOR_CONTEXT_HOOK=false
```

只有需要让其他 OTel 自动插桩继承 Agent Span，且确认宿主没有重复 Hook 时才开启。

## JDK 11 构建失败

AgentScope Java 2.0 和本 SDK 最低要求 JDK 17。设置正确的 `JAVA_HOME`，再执行：

```bash
java -version
./mvnw clean verify
```
