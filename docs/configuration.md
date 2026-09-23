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
| `CLS_CONTENT_CAPTURE` | `off` | off/hash/truncate/full | 普通消息正文、工具参数和结果的采集策略 |
| `CLS_REASONING_CAPTURE` | `off` | off/hash/truncate/full | 模型推理正文的独立采集策略 |
| `CLS_PROVIDER_PAYLOAD_CAPTURE` | `off` | off/hash/truncate/full | Provider 原始载荷的独立采集策略（0.3 新增） |
| `CLS_MAX_CONTENT_BYTES` | `950000` | 256–1000000 | 单个正文类 Attribute 的 UTF-8 字节上限 |
| `CLS_TRUNCATE_PREVIEW_BYTES` | `4096` | 256–65536 | `truncate` 模式每个截断信封保留的预览字节上限 |

0.3 起 `CLS_MAX_CONTENT_BYTES` 的物理上限收紧为 1,000,000 UTF-8 bytes（CLS 单 field
硬约束内）。0.2 配置的大于该上限的值在启动时告警并钳制，不拒绝启动。

普通正文模式（`CLS_CONTENT_CAPTURE`）：

| 模式 | 行为 |
|---|---|
| `off` | 不上传普通消息正文、工具参数和工具结果；Chat 仍记录普通输入消息 SHA-256 |
| `hash` | 普通正文属性只保存完整内容 SHA-256 和原始字节数 |
| `truncate` | 脱敏后采集，超预算时截断或摘要 |
| `full` | 尽可能采集脱敏正文，但仍受同一硬预算限制 |

Reasoning 使用同名四种模式，但 `CLS_REASONING_CAPTURE=off` 不保存推理原文或稳定 Hash，只保留非正文指标。

普通正文和 Reasoning 模式互不继承。生产环境推荐：

```bash
export CLS_CONTENT_CAPTURE=truncate
export CLS_REASONING_CAPTURE=off
```

这允许采集脱敏后的最终回答和工具内容，但不会上传模型推理原文或其稳定 Hash。`CLS_MAX_CONTENT_BYTES` 仍是最终消息 Attribute 的统一硬上限；预算不足时优先保留最终 Text，Reasoning 优先降级或移除。

单字段预算分别应用于：

- `gen_ai.input.messages`；
- `gen_ai.output.messages`；
- `gen_ai.tool.call.arguments`；
- `gen_ai.tool.call.result`。

所有采集模式都不应被视为 DLP。普通正文 `CLS_CONTENT_CAPTURE=off` 下的稳定无盐 Chat 输入 Hash 也可能关联相同输入；Reasoning `off` 不生成该 Hash。详见 [`security-and-privacy.md`](security-and-privacy.md)。

## Provider 载荷采集

`CLS_PROVIDER_PAYLOAD_CAPTURE` 独立于普通正文与 Reasoning，默认 `off`：

| 模式 | 行为 |
|---|---|
| `off` | 不采集任何 Provider 原始载荷 |
| `hash` | 只保留脱敏后 canonical 值的 SHA-256 与原始字节数；不完整时记录 `original_bytes_at_least` 下界 |
| `truncate` | 采集有界脱敏预览（`CLS_TRUNCATE_PREVIEW_BYTES`） |
| `full` | 在单调用 1.5 MiB 与内存预算内尽可能完整采集 |

Opaque 风险：signature/encrypted/base64 等不透明内容在 `full` 下可原样（有界）进入日志，SDK
无法检查密文内部是否含凭据；`truncate` 可能暴露有界前缀；`hash` 对低熵值存在字典推断风险。
三种模式均为客户显式 opt-in。可识别明文凭据执行 best-effort 脱敏，改名、自由文本或密文中的
秘密可能无法识别。

## 导出性能参数

0.3 起 SDK 使用内置的字节感知 `ClsBatchSpanProcessor` 替代通用 OTel BatchSpanProcessor：
Span 在结束时同步编码为有界记录，队列保存编码结果而非完整 `SpanData`，数量、字节与时间
触发均可计数。

| 环境变量 | 默认值 | 范围 | 说明 |
|---|---:|---:|---|
| `CLS_EXPORT_SCHEDULE_DELAY_MS` | `2000` | 50–60000 | 队列未达到批量阈值时的最长调度间隔 |
| `CLS_MAX_QUEUE_SIZE` | `4096` | 256–65536 | 已编码 Span 记录的数量上限，溢出计入 `droppedSpans` |
| `CLS_MAX_EXPORT_BATCH_BYTES` | `4194304` | 2097152–4718592 | 单次 Tencent Producer 提交的字节上限（较 5 MiB 硬限制保留 20%） |
| `CLS_MAX_EXPORT_BATCH_COUNT` | `256` | 1–10000 | 单次导出的 Span 记录数上限 |
| `CLS_PRODUCER_LINGER_MS` | `200` | 100–5000 | Tencent Producer 的批次滞留时间 |
| `CLS_MAX_PRODUCER_BUFFER_BYTES` | `67108864` | 1048576–1073741824 | Tencent Producer 总缓冲字节数 |
| `CLS_EXPORT_TIMEOUT_MS` | `30000` | 1000–600000 | 单次导出的总超时；一次 processor 导出的所有物理切片共享同一 deadline |

自定义 `SpanSink` 仍按 processor 导出批次（至多 256 条）被调用，不感知 Tencent 物理拆批。
内置 Tencent Sink 会把超限批次拆成多个 Producer submission；远端失败不二分重试。

队列不是持久队列。进程崩溃、强制停止、队列溢出或 flush 超时可能丢失尾部数据；队列与内存
溢出计入 SDK `droppedSpans`。

## 内存预算

| 环境变量 | 默认值 | 范围 | 说明 |
|---|---:|---:|---|
| `CLS_MAX_CAPTURE_MEMORY_BYTES` | `67108864` | 8 MiB–1 GiB | SDK 级采集内存总池（正文保留、控制记录、已编码队列 reservation） |
| `CLS_MAX_INVOCATION_CAPTURE_MEMORY_BYTES` | `8388608` | 1–256 MiB | 单次调用的采集内存上限，不得大于 SDK 总池 |

申请失败时该次采集降级为 Hash 或丢弃并计入容量指标，不影响业务。

## Reactor 上下文

| 环境变量 | 默认值 | 可选值 | 说明 |
|---|---:|---|---|
| `CLS_REACTOR_CONTEXT_MODE` | `private` | private/bridge/legacy_hook | Reactor 上下文传播模式（0.3 新增） |
| `CLS_REACTOR_CONTEXT_HOOK` | 无 | true/false | 0.2 兼容键，已弃用；`false` 映射 `private`，`true` 映射 `legacy_hook` 并打印弃用告警 |

模式语义：

- `private`（默认）：SDK 只使用实例级私有 Reactor Context 键传播自己的父子 Span，不触碰任何
  全局 OTel Hook；两个 CLS 实例互不影响；
- `bridge`：只在 SDK 自己的 Flux 上调用 `runWithContext`，不注册进程级 Hook；HTTP、数据库等
  其他 OTel Reactor 自动插桩可以挂到当前 Agent Span 下；
- `legacy_hook`：保持 0.2 的 `registerOnEachOperator()` 进程级注册行为。注册在进程生命周期内
  永不撤销（SDK 无法证明对全局命名槽的所有权），且可能与宿主自己管理的同类 OTel Reactor
  operator 冲突。新接入不推荐。

新旧键同时配置且语义不等价时启动报错，不静默覆盖。

## Host Trace Link

| 环境变量 | 默认值 | 说明 |
|---|---:|---|
| `CLS_HOST_TRACE_LINK_ENABLED` | `true` | 宿主已有 OTel Span 时，CLS Entry 保持独立 Trace（root），并以 OTel Link 关联宿主 SpanContext |

宿主快照在进入任何 CLS 上下文写入前捕获，同一 classloader 下多个 CLS 实例与任意注册顺序
读取同一份原始宿主上下文；快照永不保存 CLS 自己的 Span。`bridge`/`legacy_hook` 模式下嵌套
自动插桩挂接不受影响。设为 `false` 则完全不写 Link。

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

## HITL 与外部执行

| 环境变量 | 默认值 | 范围 | 说明 |
|---|---:|---:|---|
| `CLS_HITL_WAIT_TIMEOUT_MS` | `600000` | 1000–86400000 | 单次 HITL/外部执行等待的最长挂起时间，超时以 `await_timeout` 收口并允许迟到结果一次性 rotate 新 generation |

## 关闭与健康度

| 环境变量 | 默认值 | 范围 | 说明 |
|---|---:|---:|---|
| `CLS_SHUTDOWN_TIMEOUT_MS` | `45000` | 1000–600000 | 优雅关闭的总预算 |

```java
boolean flushed = observability.flush(Duration.ofSeconds(5));
boolean stopped = observability.shutdown(Duration.ofSeconds(45));
ClsTelemetrySnapshot snapshot = observability.snapshot();
observability.close();
```

- `flush()` 单飞合并并发调用，每个调用方只用自己的等待 deadline；超时或失败返回 `false`，
  不应改变业务结果；DRAINING 后立即返回 `false`；
- `shutdown(Duration)` 立即进入 DRAINING（拒绝新根调用，已注册调用继续），随后按单一绝对
  deadline 的 40%/65%/85%/100% 里程碑依次等待静默、冻结、processor flush、Sink 屏障与
  provider 关闭；返回 `false` 表示未能在预算内完成，后续调用可从未完成阶段继续；
- `close()` 等价于 `shutdown(配置的 shutdownTimeout)`，幂等，关闭失败计数但不抛给业务；
- `acceptedSpans`：Sink 成功接收；
- `invalidSpans`：Schema 校验拒绝；
- `exportFailures`：导出或生命周期失败；
- `droppedSpans`：SDK 可观察到的保护性跳过（含队列/内存溢出）。
