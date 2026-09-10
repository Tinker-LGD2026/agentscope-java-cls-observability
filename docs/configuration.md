# 配置参考

SDK 默认从环境变量创建配置：

```java
ClsObservabilityConfig config =
        ClsObservabilityConfig.fromEnvironment(System.getenv());
```

也可以使用 `ClsObservabilityConfig.builder()` 在代码中配置。生产环境推荐由部署平台注入环境变量，避免凭据进入源码。

## 输出模式

| 环境变量 | 默认 | 可选值 | 说明 |
|---|---:|---|---|
| `CLS_TRANSPORT` | 自动判断 | `console`、`cloud` | 未配置云参数时默认 Console；出现任一云参数时尝试 Cloud |

Console 模式将完整 CLS Span JSON 写入标准输出，仅适合本地开发。开启正文采集时，Console 输出也可能包含业务内容。

## Cloud 必填项

| 环境变量 | 说明 |
|---|---|
| `CLS_ENDPOINT` | CLS 地域接入点，不带路径、查询参数或自定义端口 |
| `CLS_TOPIC_ID` | Agent 可观测应用关联的 Trace 日志主题 ID，不是应用 ID |
| `CLS_SECRET_ID` | CAM SecretId |
| `CLS_SECRET_KEY` | CAM SecretKey |
| `CLS_SECRET_TOKEN` | 临时凭证 Token，可选；使用临时凭证时必填 |

公网示例：

```bash
export CLS_TRANSPORT=cloud
export CLS_ENDPOINT='ap-shanghai.cls.tencentcs.com'
export CLS_TOPIC_ID='<trace-topic-id>'
export CLS_SECRET_ID='<secret-id>'
export CLS_SECRET_KEY='<secret-key>'
```

腾讯云 CVM/TKE 内网示例：

```bash
export CLS_ENDPOINT='ap-shanghai.cls.tencentyun.com'
```

安全校验：

- 自动补充 `https://`；
- 只接受腾讯云 CLS 公网或内网域名；
- 拒绝 HTTP、userinfo、自定义端口、路径、query 和 fragment；
- Endpoint 与 Topic 必须位于同一地域。

## 服务标识

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `CLS_SERVICE_NAME` | `agentscope-java-app` | 写入 Resource 的 `service.name`，长度 1–128 |
| `CLS_DEPLOYMENT_ENVIRONMENT` | 无 | 写入 `deployment.environment.name`，如 `development`、`staging`、`production` |

`host.name` 由 SDK 从运行环境获取。容器中通常是容器或 Pod hostname。

Entry 顶层名称固定为 `enter_application`，不会拼接 `service.name`。

## 正文采集

| 环境变量 | 默认值 | 范围 | 说明 |
|---|---:|---:|---|
| `CLS_CONTENT_CAPTURE` | `off` | off/hash/truncate/full | 正文采集策略 |
| `CLS_MAX_CONTENT_BYTES` | `1100000` | 256–1100000 | 单个正文类 Attribute 的 UTF-8 字节上限 |

模式：

| 模式 | 行为 |
|---|---|
| `off` | 不上传消息正文、工具参数和工具结果；Chat 仍记录输入消息 SHA-256 |
| `hash` | 正文属性只保存完整内容 SHA-256 和原始字节数 |
| `truncate` | 脱敏后采集，超预算时截断或摘要 |
| `full` | 尽可能采集脱敏正文，但仍受同一硬预算限制 |

单字段预算分别应用于：

- `gen_ai.input.messages`；
- `gen_ai.output.messages`；
- `gen_ai.tool.call.arguments`；
- `gen_ai.tool.call.result`。

所有采集模式都不应被视为 DLP。`off` 模式中的稳定无盐 Hash 也可能关联相同输入，详见 [`security-and-privacy.md`](security-and-privacy.md)。

## 导出性能参数

| 环境变量 | 默认值 | 范围 | 说明 |
|---|---:|---:|---|
| `CLS_EXPORT_SCHEDULE_DELAY_MS` | `2000` | 50–60000 | 队列未达到批量阈值时的最长调度间隔 |
| `CLS_MAX_QUEUE_SIZE` | `4096` | 256–65536 | OTel 内存队列的 Span 数量上限 |

单次 OTel 导出批量自动计算：

```text
maxExportBatchSize = max(64, min(256, CLS_MAX_QUEUE_SIZE / 8))
```

默认队列 4096 时，单批上限为 256 Span。

队列不是持久队列。进程崩溃、强制停止、队列溢出或 flush 超时可能丢失尾部数据。OTel 内部队列溢出不计入 SDK `droppedSpans`，应同时监控应用日志和 OTel 指标。

## Reactor 上下文

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `CLS_REACTOR_CONTEXT_HOOK` | `false` | 是否注册进程级 OpenTelemetry Reactor Hook |

默认关闭时，SDK 使用私有 Reactor Context 键传播自己的父子 Span，调用树仍完整。

只有需要让 HTTP、数据库等其他 OTel 自动插桩挂到当前 Agent Span 下，并且确认宿主没有重复管理相同 Hook 时才开启。进程级 Hook 无法在 SDK 关闭时安全移除。

## RuntimeContext

每次顶层 Agent 调用至少传：

```java
RuntimeContext context = RuntimeContext.builder()
        .sessionId(conversationId)
        .userId(currentUserId)
        .build();
```

可选高级字段：

```java
RuntimeContext context = RuntimeContext.builder()
        .sessionId(conversationId)
        .userId(currentUserId)
        .put(
                ClsInvocationContext.class,
                new ClsInvocationContext(
                        displayName,
                        businessRequestId,
                        "customer-service-agent",
                        "web-api"))
        .build();
```

规则：

- 缺少 `sessionId` 或 `userId` 时跳过本次遥测，不影响 Agent；
- 同一连续会话复用相同 `sessionId`；
- 默认每次顶层调用生成新的 `turnId` 和 Trace；
- 显式 `turnId` 应对应唯一业务请求，不能跨轮复用；
- `userId` 使用不可直接识别个人的内部 ID；
- 后端必须验证 `sessionId` 属于当前认证用户，不能直接信任前端任意传入的会话 ID；
- `sessionId`、`userId`、`turnId` 最多 512 UTF-8 bytes；`userName` 最多 256 bytes；`agentType`、`entryType` 最多 128 bytes；
- 超长身份值会保留有界前缀并追加稳定 SHA-256 短指纹；
- 子 Agent 自动继承父调用上下文。

## 凭据生命周期

```java
ClsObservabilityConfig config =
        ClsObservabilityConfig.fromEnvironment(System.getenv());
ClsAgentObservability observability =
        ClsAgentObservability.create(config);
config.destroyCredentials();
```

销毁后该配置对象不能再次用于创建 Cloud Transport。`toString()` 不输出凭据值。

## 关闭与健康度

```java
boolean flushed = observability.flush(Duration.ofSeconds(5));
ClsTelemetrySnapshot snapshot = observability.snapshot();
observability.close();
```

- `flush()` 超时或失败返回 `false`，不应改变业务结果；
- `close()` 幂等，关闭失败不抛给业务；
- `acceptedSpans`：Sink 成功接收；
- `invalidSpans`：Schema 校验拒绝；
- `exportFailures`：导出或生命周期失败；
- `droppedSpans`：SDK 可观察到的保护性跳过。
