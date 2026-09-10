# 公共 API

当前版本为 `0.1.x` 公开预览。1.0.0 前，minor 版本可能调整 API。

## 稳定预览 API

| 类型 | 用途 | 生命周期/线程说明 |
|---|---|---|
| `ClsObservabilityConfig` | 配置输出、凭据、正文和队列 | 创建期使用；Cloud 创建后可销毁凭据副本 |
| `ClsObservabilityConfig.Builder` | 代码式配置 | 请求外构建，不应每轮创建 |
| `ClsAgentObservability` | SDK 门面、Middleware、flush、close、snapshot | 应用级；退出时关闭 |
| `ClsInvocationContext` | 可选 User Name、Turn、Agent/Entry 类型 | 请求级不可变值 |
| `ClsTelemetrySnapshot` | accepted/invalid/failure/dropped 计数快照 | 不可变快照 |
| `ContentCaptureMode` | off/hash/truncate/full | 配置枚举 |

## 扩展 API

| 类型 | 用途 | 所有权 |
|---|---|---|
| `SpanSink` | 自定义输出端 | 传入 `ClsAgentObservability.create(config, sink)` 后由 SDK 生命周期管理并关闭 |
| `ClsSpanRecord` | 经过编码的 CLS Span | 不可变记录；字段值均为 CLS 字符串格式 |

自定义 Sink 必须：

- 支持异步 `CompletionStage` 完成语义；
- 不在调用线程无限阻塞；
- 失败时异常完成；
- 正确实现 `flush(timeout)`；
- `close()` 可幂等调用或安全失败。

## 非稳定实现 API

以下包主要供 SDK 内部使用，即使类型因模块边界暂时为 public，也不属于兼容承诺：

```text
*.exporter
*.instrumentation
*.internal
*.schema（ClsSpanRecord 除外）
*.transport（SpanSink 除外）
```

不要直接创建 `ClsTracingMiddleware`、`ClsSpanExporter`、`ClsSpanEncoder`、`ClsSpanValidator` 或腾讯 CLS Transport。应从 `ClsAgentObservability.create(...)` 进入。

## AgentScope 对象生命周期

`ReActAgent` 不是本 SDK提供的类型，也不是并发共享对象。推荐：

- 每请求创建并关闭；或
- 按 Session 管理，保证同一实例严格串行，并在 Session 过期时关闭。

`RuntimeContext` 每次顶层调用创建。子 Agent通过 AgentScope 自动继承。

身份字段会在写入 Span 前按 UTF-8 字节有界化：Session/User/Turn 为 512 bytes，User Name 为 256 bytes，Agent/Entry Type 为 128 bytes。超长值使用有界前缀和稳定 SHA-256 短指纹，保证同一原值稳定映射且不同长值不易碰撞。
