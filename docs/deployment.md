# 部署指南

SDK 是嵌入客户 AgentScope 应用的 Java 库，不是独立服务。以下模板中的 `app.jar` 代表已经集成本 SDK 的客户应用。

## 通用要求

- JDK 17+；
- 应用启动时创建一个 `ClsAgentObservability`；
- 每次顶层 Agent 调用传 `RuntimeContext`；
- JVM 正常退出时执行 `flush()` 和 `close()`；
- Secret 只在运行时注入；
- Endpoint 与 Topic 位于同一地域（部署前提，启动会校验）；
- 生产默认 `CLS_CONTENT_CAPTURE=off`、`CLS_REASONING_CAPTURE=off`、
  `CLS_PROVIDER_PAYLOAD_CAPTURE=off`、`CLS_REACTOR_CONTEXT_MODE=private`；
- 编排层优雅停机预算 ≥60 秒（Kubernetes `terminationGracePeriodSeconds`、Compose
  `stop_grace_period`、systemd `TimeoutStopSec`），覆盖默认 45 秒的
  `CLS_SHUTDOWN_TIMEOUT_MS` 关闭链。

## 本地开发

### macOS / Linux

```bash
java -version
./mvnw clean verify
./mvnw install -DskipTests
./mvnw -f demo/pom.xml exec:java
```

系统默认是 JDK 11 时，安装 JDK 17 并设置：

```bash
export JAVA_HOME='<jdk-17-home>'
export PATH="$JAVA_HOME/bin:$PATH"
java -version
```

Cloud 联调使用公网 Endpoint：

```bash
export CLS_TRANSPORT=cloud
export CLS_ENDPOINT='ap-shanghai.cls.tencentcs.com'
export CLS_TOPIC_ID='<trace-topic-id>'
export CLS_SECRET_ID='<secret-id>'
export CLS_SECRET_KEY='<secret-key>'
```

### Windows PowerShell

```powershell
$env:JAVA_HOME = '<jdk-17-home>'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:CLS_TRANSPORT = 'cloud'
$env:CLS_ENDPOINT = 'ap-shanghai.cls.tencentcs.com'
$env:CLS_TOPIC_ID = '<trace-topic-id>'
$env:CLS_SECRET_ID = '<secret-id>'
$env:CLS_SECRET_KEY = '<secret-key>'
.\mvnw.cmd clean verify
```

## CVM / systemd

腾讯云 CVM 能访问同地域 CLS 内网时，优先：

```text
ap-shanghai.cls.tencentyun.com
```

推荐目录：

```text
/opt/agent-app/app.jar
/etc/agent-app/cls.env
/etc/systemd/system/agentscope-agent.service
```

创建专用账号、目录并安装宿主应用和模板：

```bash
sudo useradd --system --home /opt/agent-app --shell /usr/sbin/nologin agentapp
sudo install -d -o root -g agentapp -m 0750 /opt/agent-app
sudo install -d -o root -g root -m 0750 /etc/agent-app
sudo install -o root -g agentapp -m 0440 \
  /path/to/customer-app.jar /opt/agent-app/app.jar
sudo install -o root -g root -m 0644 \
  deploy/systemd/agentscope-agent.service.example \
  /etc/systemd/system/agentscope-agent.service
sudo install -o root -g root -m 0600 \
  deploy/systemd/cls.env.example \
  /etc/agent-app/cls.env
```

如果 `agentapp` 已存在，跳过 `useradd`。确认 `/usr/bin/java` 指向 JDK 17 或 21。

编辑 `/etc/agent-app/cls.env` 后：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now agentscope-agent
sudo systemctl status agentscope-agent
```

要求：

- EnvironmentFile 权限 `0600`；
- 不在 unit 文件中直接写 Secret；
- `TimeoutStopSec` 覆盖应用 flush/close；
- 使用最小权限 CAM 身份；
- 主机必须能访问目标 CLS Endpoint 的 TCP 443 和 DNS。

## Docker

模板：`deploy/docker/Dockerfile.example`。它是客户宿主应用的运行时模板，不会构建本仓库，也不会假设存在 `your-application` 模块。请先在自己的宿主项目生成可执行 JAR：

```bash
./mvnw clean package
```

根目录 `.dockerignore` 使用默认拒绝白名单：除了 `dist/app.jar` 和 Dockerfile，其他文件都不会进入 Docker/BuildKit 上下文。若将模板复制到客户项目，也必须保留等价的白名单；不要改回凭据扩展名黑名单或 `COPY . .`。

构建镜像时不能使用：

```dockerfile
ENV CLS_SECRET_KEY=...
ARG CLS_SECRET_KEY=...
COPY .env .
```

把宿主 JAR 复制到专用、可审查的 `dist` 目录后构建；`target` 目录仍由 `.dockerignore` 排除：

```bash
mkdir -p dist
cp target/customer-agent.jar dist/app.jar
docker build \
  -f deploy/docker/Dockerfile.example \
  --build-arg APP_JAR=dist/app.jar \
  -t my-agent:0.1.0 .
docker run --rm \
  --read-only \
  --tmpfs /tmp:size=64m,mode=1777 \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --env-file /secure/path/cls.env \
  my-agent:0.1.0
```

`/secure/path/cls.env` 必须位于仓库外并限制文件权限。

Docker Compose 使用推送到镜像仓库后的不可变 digest。先设置变量，再启动：

```bash
export AGENT_IMAGE_DIGEST='registry.example.com/team/my-agent@sha256:<digest>'
export CLS_ENV_FILE='/secure/path/cls.env'
docker compose -f deploy/docker/compose.yaml up -d
```

不要把 `AGENT_IMAGE_DIGEST` 设置为 `latest` 或普通版本 Tag。

## TKE / Kubernetes

非敏感项放 ConfigMap：

```bash
kubectl apply -f deploy/kubernetes/configmap.yaml
```

Secret 通过命令创建，不提交 Secret YAML：

```bash
export CLS_SECRET_ID='<secret-id>'
export CLS_SECRET_KEY='<secret-key>'
export CLS_SECRET_TOKEN=''
sh deploy/kubernetes/create-secret.sh.example
```

使用不可变镜像摘要渲染并首次部署：

```bash
export AGENT_IMAGE='<registry>/<project>/agent-app@sha256:<digest>'
envsubst '${AGENT_IMAGE}' \
  < deploy/kubernetes/deployment.yaml \
  | kubectl apply -f -
```

`deployment.yaml` 中的 `${AGENT_IMAGE}` 必须经过 `envsubst` 渲染；不要直接提交或应用未替换的模板。后续更新使用新的 digest 重新执行同一命令。

注意：

- TKE 同地域优先 CLS 内网 Endpoint；
- Secret 使用最小 RBAC；
- `terminationGracePeriodSeconds` 至少 30 秒；
- 宿主应用必须在 SIGTERM 时执行 `flush()` 和 `close()`；
- readiness/liveness 由宿主应用提供，SDK 不提供 HTTP 健康端点；
- 同一 `ReActAgent` 不可由多个请求并发共享；
- 多副本下 Session 依靠客户传入的 `sessionId` 聚合，不依赖 Pod 粘性。

## 运行健康度

应用应把以下值接入自己的健康指标或日志：

```java
ClsTelemetrySnapshot snapshot = observability.snapshot();
```

重点告警：

- `invalidSpans` 增长；
- `exportFailures` 增长；
- `droppedSpans` 增长；
- OTel BatchSpanProcessor 队列溢出日志。

SDK 的内存队列不是持久队列；不能用作审计或计费事实源。
