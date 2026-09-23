# 安全与隐私

## 威胁模型

SDK 处在模型消息、工具参数、工具结果、用户身份和云端日志之间。主要风险包括：

- 凭据进入源码、镜像、日志或 Git 历史；
- Prompt、模型输出或工具结果包含个人信息和业务秘密；
- 稳定 Hash 被用于关联或字典推断；
- 配置恶意 Endpoint 导致凭据外发；
- 无界正文或响应造成内存压力；
- 遥测故障影响客户 Agent。

## 默认采集

即使正文关闭，Span 仍可能包含：

- Session ID、Turn ID；
- User ID、可选 User Name；
- Agent、模型、Provider、Tool 名称；
- Token、耗时和状态；
- `host.name`、`service.name`；
- Chat 输入消息的稳定 SHA-256。

这些数据仍需纳入客户的数据分类、访问控制、地域和保留策略。

宿主后端必须验证 `sessionId` 属于当前认证用户，不能直接信任前端任意会话 ID。`sessionId`、`userId`、`turnId` 最多 512 UTF-8 bytes，`userName` 最多 256 bytes，类型字段最多 128 bytes；超长值会被有界前缀加稳定 SHA-256 短指纹替代，但这不等同于匿名化。

## 正文模式

| 模式 | 上传正文 | 内容派生值 | 使用建议 |
|---|---|---|---|
| `off` | 否 | 输入消息 Hash | 生产默认 |
| `hash` | 否 | Hash + 原始字节数 | 需要关联相同内容时谨慎使用 |
| `truncate` | 是，脱敏且有界 | Hash/摘要 | 临时排障优先 |
| `full` | 是，脱敏且有界 | Hash/摘要 | 仅明确授权的受控环境 |

Hash 是稳定、无盐 SHA-256：

- 不包含原文；
- 可以判断两次输入是否相同；
- 对固定短语、布尔值、短编号等低熵内容可能被字典枚举；
- 不能当作匿名化或加密。

普通 Chat 输入在正文 `off` 时仍保留稳定输入 Hash。如果业务禁止任何普通内容派生值离开进程，当前版本不满足该要求，需要在接入前扩展独立输入 Hash 开关。

## Reasoning 特殊风险

模型推理内容可能包含系统提示片段、用户敏感信息、工具中间参数，以及未经过最终回答过滤的判断，因此默认使用独立的 `CLS_REASONING_CAPTURE=off`：

- 普通正文开启不会自动开启 Reasoning；
- Reasoning `off` 只保留存在性、块数、原始字节数和时序指标，不保存原文或稳定 Hash；
- Reasoning `hash` 使用稳定无盐 SHA-256，仍可关联相同低熵内容；
- `truncate/full` 只采集 `ThinkingBlock.getThinking()`，仍执行脱敏和硬预算；
- signature、thought signature、encrypted/redacted reasoning 和 `ThinkingBlock.metadata` 永不进入 Span。

## Provider 载荷与 Opaque 内容

`CLS_PROVIDER_PAYLOAD_CAPTURE` 默认 `off`。开启前必须理解：

- `full` 可原样（有界）采集 signature/encrypted/base64 等不透明内容，SDK 无法检查密文内部
  是否含凭据；
- `truncate` 可能暴露不透明值的有界前缀；
- `hash` 对低熵不透明值存在字典推断风险；
- 三种模式均为显式 opt-in，不构成 DLP 替代品。

可识别明文凭据执行 best-effort 脱敏；改名、自由文本或密文中的秘密可能无法识别。

## Source IP

CLS Producer 上报的日志会携带宿主 egress 的 source IP（CLS 服务端记录）。该值可用于区分
上报来源主机，但不等于经过验证的客户端身份；接入方应将其纳入网络与访问控制评估。

## 脱敏能力

`truncate/full` 会处理：

- secret、token、password、authorization、cookie、api key 等敏感键；
- PEM 私钥、`AKID`、`sk-`、Bearer Token、JWT、常见键值密钥形态；
- URL userinfo、query、fragment；
- 超过 32 层的嵌套对象；
- 超过单字段 1,000,000 UTF-8 bytes 的正文。

限制：

- 正则和键名脱敏不可能识别所有业务秘密；
- 业务自定义账号、订单、医疗、金融和个人数据需要客户在进入 SDK 前处理；
- 不应将脱敏视为 DLP、用户授权、保留策略或合规审查的替代品。

## 凭据

必须：

- 使用 CAM 子账号和最小权限；
- 优先使用短期凭证（临时凭证过期后 SDK 不会自动刷新，需要重启或重建 SDK 实例）；
- 通过环境变量、Secret Manager、systemd EnvironmentFile 或 Kubernetes Secret 注入；
- 创建 SDK 后调用 `config.destroyCredentials()` 清除配置对象副本；
- 定期轮换；
- 泄露后立即吊销，不以删除 Git 文件代替轮换。

禁止：

- 硬编码在 Java、YAML、Dockerfile 或 Shell 脚本；
- 写入 GitHub Actions 日志或 Artifact；
- 放入 Docker `ARG` / `ENV` 或镜像层；
- 提交 base64 编码的 Kubernetes Secret；
- 在 Issue 中粘贴。

## Endpoint 防护

Cloud Endpoint：

- 强制 HTTPS；
- 只允许腾讯云 CLS 公网/内网域名；
- 禁止 userinfo、端口、路径、query、fragment。

旅行 Demo 的 Open-Meteo 客户端：

- 固定公开域名；
- 禁止自动重定向；
- 5 秒连接超时；
- 12 秒请求超时；
- 25 秒工具总预算；
- 1 MiB 响应体硬上限；
- 不回显上游错误正文。

## 不采集的数据

当前明确不采集：

- `gen_ai.input.messages_delta`；
- cwd；
- Git 仓库、分支、Remote 和 Commit；
- Java 堆栈正文；
- CLS 或模型凭据。

## 数据治理责任

客户负责：

- 选择 CLS 地域；
- Topic 访问控制；
- 数据保留和删除；
- 用户告知与授权；
- 跨境和行业合规；
- 正文模式审批；
- 对 Session/User 标识进行假名化；
- 定期审计查询和下载权限。

## 漏洞报告

请使用 GitHub Private Vulnerability Reporting。不要在公开 Issue 中披露漏洞细节、凭据或客户数据。详见仓库根目录 `SECURITY.md`。
