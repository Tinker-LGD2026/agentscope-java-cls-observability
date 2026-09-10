# GitHub 仓库创建与首次发布

## 1. 创建空仓库

在 GitHub 账号或组织 `Tinker-LGD2026` 下创建：

```text
agentscope-java-cls-observability
```

建议设置：

- Visibility：Public；
- 不勾选自动创建 README；
- 不勾选自动创建 LICENSE；
- 不勾选自动创建 `.gitignore`；
- Issues：开启；
- Discussions：按需开启；
- Private Vulnerability Reporting：开启；
- Secret Scanning：开启；
- Push Protection：开启。

创建空仓库可以避免远端初始提交与本地文件冲突。

## 2. 首次提交前门禁

在本地项目根目录确认：

```bash
./mvnw clean verify
```

并完成：

- JDK 17 全量测试；
- JDK 21 全量测试；
- 工作树凭据扫描；
- `测试信息.md` 和内部过程资料不在公开目录；
- README 本地链接检查；
- GitHub Actions YAML 检查；
- SDK JAR 内容检查。

曾用于联调的 CLS 和模型凭据必须先在对应平台撤销并轮换。

## 3. 初始化本地仓库

只有完成凭据轮换后再执行：

```bash
git init -b main
git add -n .
```

先检查 dry-run 列表。不得出现：

```text
.private/
.live-data/
.tools/
target/
测试信息.md
.env
*.pem
*.key
```

确认后：

```bash
git add .
git status --short
git commit -m "feat: publish AgentScope CLS observability SDK preview"
```

## 4. 连接并推送

```bash
git remote add origin \
  git@github.com:Tinker-LGD2026/agentscope-java-cls-observability.git
git push -u origin main
```

禁止 Force Push 到 `main`。

## 5. 分支保护

在 GitHub 仓库 Settings 中为 `main` 配置：

- Require a pull request before merging；
- Require status checks to pass；
- Require branches to be up to date；
- Require conversation resolution；
- Block force pushes；
- Block deletions。

先完成首次 Push，让 GitHub 实际生成检查名称，再从 Rulesets 页面选择必需检查，不要手工猜名称。当前工作流预期显示：

```text
CI / JDK 17
CI / JDK 21
CI / Documentation links
CodeQL / Analyze Java
Dependency Review / dependency-review
Secret Scan / gitleaks
```

同时创建 `v*` Tag ruleset：限制 Tag 创建和删除，禁止 Force Update。创建名为 `release` 的 GitHub Environment，并为发布配置人工审批；Release 构建作业只读，只有该 Environment 保护的发布作业拥有 `contents: write`。

## 6. 创建首个 Release

确认根 POM 版本为 `0.1.0`，然后：

```bash
git tag -a v0.1.0 -m "AgentScope CLS Observability SDK 0.1.0"
git push origin v0.1.0
```

`release.yml` 会自动：

1. 校验 Tag 与 POM 版本；
2. 执行完整测试；
3. 生成 SDK JAR、Sources、Javadoc、SDK 运行时 SBOM 和 AgentScope 消费者基线 SBOM；
4. 对两个 SBOM 执行 OSV 扫描并生成 SHA-256；
5. 创建 GitHub Release。

本阶段不执行 Maven Central 或 GitHub Packages 发布。

## 7. 发布后验证

在空目录重新 clone：

```bash
git clone https://github.com/Tinker-LGD2026/agentscope-java-cls-observability.git
cd agentscope-java-cls-observability
git checkout v0.1.0
./mvnw clean install
./mvnw -f demo/pom.xml exec:java
```

同时下载 Release 资产并校验：

```bash
sha256sum -c SHA256SUMS
jar tf agentscope-cls-observability-sdk-0.1.0.jar
```

确认 JAR 中没有 Demo、测试、内部文档、环境文件或凭据。
