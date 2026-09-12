# GitHub 仓库维护手册

本仓库的初始创建、分支保护、Tag 保护和 `release` Environment 已在 v0.1.0 首次发布时完成并启用。本文档描述持续维护所需的配置基线；如需重建仓库或审计配置，以本文为准。

## 1. 仓库基线

- Visibility：Public；
- 仓库名：`Tinker-LGD2026/agentscope-java-cls-observability`；
- 默认分支：`main`；
- 开启：Issues、Private Vulnerability Reporting、Secret Scanning、Push Protection、Dependabot Alerts 与 Security Updates；
- 合并后自动删除分支。

## 2. 分支与 Tag 保护

`main` 分支保护要求：

- 必须通过 Pull Request 合并；
- 必需状态检查全部通过且分支为最新；
- 会话必须 resolve；
- 禁止 Force Push 与删除。

必需检查名称（以 GitHub 实际生成为准）：

```text
CI / JDK 17
CI / JDK 21
CI / Documentation links
CodeQL / Analyze Java
Dependency Review / dependency-review
Secret Scan / gitleaks
```

`v*` Tag Ruleset：仅仓库管理员可创建，禁止删除与重写（non-fast-forward）。

`release` Environment：发布作业需人工审批；只有该 Environment 保护的作业拥有 `contents: write`，构建作业保持只读。

## 3. 日常变更门禁

任何公开内容合入前，本地或 CI 必须完成：

```bash
./mvnw clean verify
```

并确认：

- JDK 17、JDK 21 全量测试通过；
- 双 SBOM（SDK 运行时 + AgentScope 消费者基线）OSV 扫描为 0；
- Gitleaks 扫描公开树与 Git 历史无泄漏；
- 内部过程资料（计划、证据、联调输出、凭据文件）始终留在 `.private/` 且不进入提交；
- 曾用于联调的凭据先撤销并轮换。

## 4. 发布流程

确认根 POM 版本与目标 Tag 一致（`vX.Y.Z` 对应 POM `X.Y.Z`），然后按根目录 `RELEASING.md` 执行：

```bash
git tag -a vX.Y.Z -m "AgentScope CLS Observability SDK X.Y.Z"
git push origin vX.Y.Z
```

`release.yml` 会自动：

1. 校验 Tag 与 POM 版本；
2. 执行完整测试；
3. 生成 SDK JAR、Sources、Javadoc、SDK 运行时 SBOM 和 AgentScope 消费者基线 SBOM；
4. 对两个 SBOM 执行 OSV 扫描并生成 SHA-256；
5. 在 `release` Environment 审批后创建 GitHub Release。

本阶段不执行 Maven Central 或 GitHub Packages 发布。

## 5. 发布后验证

在空目录重新 clone：

```bash
git clone https://github.com/Tinker-LGD2026/agentscope-java-cls-observability.git
cd agentscope-java-cls-observability
git checkout vX.Y.Z
./mvnw clean install
./mvnw -f demo/pom.xml exec:java
```

同时下载 Release 资产并校验：

```bash
sha256sum -c SHA256SUMS
jar tf agentscope-cls-observability-sdk-X.Y.Z.jar
```

确认 JAR 中没有 Demo、测试、内部文档、环境文件或凭据。
