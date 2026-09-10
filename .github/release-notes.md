## AgentScope Java CLS Observability SDK 0.1.0

首个公开预览版本，提供 AgentScope Java 2.0.3 的 Entry、Agent、Step、Chat、Tool 和原生子 Agent CLS Trace 采集。

### 安装方式

本阶段仅发布 GitHub 源码和 Release 资产，**尚未发布 Maven Central 或 GitHub Packages**。请 clone 对应 Tag 并安装到本机 Maven 仓库：

```bash
git clone https://github.com/Tinker-LGD2026/agentscope-java-cls-observability.git
cd agentscope-java-cls-observability
git checkout v0.1.0
./mvnw clean install
```

### 兼容性

- 最低 JDK 17；
- 已验证 JDK 17、21；
- 已验证 AgentScope Java 2.0.3；
- 其他 AgentScope 2.x 尚未承诺兼容。

### 安全提示

- 正文默认关闭；
- `off` 模式仍记录稳定输入消息 SHA-256；
- SDK 与公开 Demo 排除未使用的 MCP 传输栈；
- Release 附带 SDK 运行时 SBOM、AgentScope 2.0.3 消费者基线 SBOM 和 SHA-256 校验文件；
- 不要在 Issue、日志或配置仓库中提交凭据及客户正文。

详细功能、限制和部署方式见仓库 `README.md`、`SECURITY.md` 与 `docs/`。
