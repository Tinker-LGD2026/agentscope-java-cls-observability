## AgentScope Java CLS Observability SDK 0.2.0

第二个公开预览版本：新增 Provider 无关的通用 Reasoning（思考模式）可观测性。

### 新增

- Chat Span 按原始顺序输出 AgentScope `ThinkingBlock` / Text / Tool Call 三类中性 parts；
- 独立隐私开关 `CLS_REASONING_CAPTURE`（`off`/`hash`/`truncate`/`full`，默认 `off`）——普通正文开启不会自动上传推理内容；
- Reasoning 指标：`present`、块数、原始字节数、推理耗时、推理/回答首片段时间、截断与畸形事件计数；
- 旅行 Demo 增加显式 `TRAVEL_ENABLE_REASONING` 开关（默认 `false`）。

### 安全与隐私

- Reasoning `off` 不上传推理原文或稳定 Hash；signature、加密推理和 `ThinkingBlock.metadata` 永不进入 Span；
- 上游未提供 Reasoning Token 时不再误写 `gen_ai.usage.reasoning_output_tokens=0`；
- 消息转换与流式累加具备固定内存/part 上限，最终预算优先保留最新最终回答与 Tool 身份；
- 不完整的消息转换不再发布误导性的输入 Hash。

### 已验证

- JDK 17 / 21 全量测试通过；
- 双 SBOM（SDK 运行时 + AgentScope 2.0.3 消费者基线）OSV 扫描 0 发现；
- DeepSeek `deepseek-chat` 开启 Thinking 的真实联调通过：Console 与 CLS 云端双模式，reasoning/text 顺序、隐私隔离与投递计数符合设计；
- 其他 Provider 是否产生完整 Thinking 事件取决于对应 AgentScope Extension，见 `docs/compatibility.md`。

### 安装方式

本阶段仅发布 GitHub 源码和 Release 资产，**尚未发布 Maven Central 或 GitHub Packages**：

```bash
git clone https://github.com/Tinker-LGD2026/agentscope-java-cls-observability.git
cd agentscope-java-cls-observability
git checkout v0.2.0
./mvnw clean install
```

### 兼容性

- 最低 JDK 17；已验证 JDK 17、21；
- 已验证 AgentScope Java 2.0.3；其他 2.x 未承诺兼容；
- 新增公共配置属性 `reasoningCaptureMode` 与 `CLS_REASONING_CAPTURE`；既有 `0.1.0` 行为默认不变。
