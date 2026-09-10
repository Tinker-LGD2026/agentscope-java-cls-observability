# 部署模板

这些文件演示如何为“已经集成 SDK 的客户 Agent 应用”注入 CLS 配置。SDK 本身不是独立服务。

- `systemd/`：CVM 或物理机；
- `docker/`：固定基础镜像的运行时模板和 Compose；
- `kubernetes/`：TKE/Kubernetes ConfigMap、Secret 引用和 Deployment。

使用前必须：

1. 将模板中的地域、Topic、镜像和应用路径替换为实际值；
2. 将 Secret 存在仓库外或平台 Secret 管理系统；
3. Docker 构建时保留根目录等价的 `.dockerignore`，只从 `dist/` 复制已构建 JAR；
4. 容器和 TKE 使用非 root、只读根文件系统、最小 Linux capabilities 和不可变镜像 digest；
5. 确保宿主应用在 SIGTERM 时执行 `flush()` 和 `close()`；
6. 根据宿主应用补充端口、readiness、liveness 和资源规格；
7. 使用最小权限 CAM 身份。

详见 [`../docs/deployment.md`](../docs/deployment.md)。
