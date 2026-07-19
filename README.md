# 易嘉轩的个人作品集 / Yi Jiaxuan Portfolio

一个面向游戏开发与 Unreal Engine 学习经历的中英文个人作品集，同时包含可维护的内容后台、发布系统和生产运维工具。

This repository contains Yi Jiaxuan's bilingual game-development portfolio, its content-management console, and the operational tooling used to publish it safely.

## 能力概览

- `frontend/`：Vue 3 公共站点，中英文路由、项目详情、SSR 页面外壳、SEO 与无障碍支持。
- `admin-web/`：Vue 3 管理端，管理站点资料、项目、媒体、发布、留言、统计与系统状态。
- `backend-parent/`：Java 17、Spring Boot 3.5、PostgreSQL 17 后端；包含 TOTP 登录、服务端会话、内容版本、媒体适配、留言、隐私友好统计和审计。
- `deploy/`：Ubuntu 22.04、Docker 26、宝塔 Nginx 的发布、预检、备份、恢复和回滚工具。

生产结构是一个模块化单体：宝塔 Nginx 独占公网 `80/443`，应用只监听宿主机回环地址 `127.0.0.1:18080`，PostgreSQL 仅存在于 Compose 私有网络。仓库不会连接或复用其他项目的数据库。

## 本地开发

准确版本由各工程的锁文件和 Maven Enforcer 约束。常用入口如下：

```text
frontend/        Node 22.18，公共站点
admin-web/       Node 22.18，管理端
backend-parent/  Java 17 + Maven 3.9.11，后端
```

前端：

```bash
cd frontend
npm ci
npm run dev
```

管理端：

```bash
cd admin-web
npm ci
npm run dev
```

后端：

```powershell
# 首次运行：复制后填写所有留空值；.env.local 已被 Git 忽略。
Copy-Item .env.example .env.local

# 只启动本项目独立的 PostgreSQL 17。
docker compose --env-file .env.local -f deploy/compose.dev.yml up -d postgres

# Spring 不会自动读取 dotenv；把同一文件注入当前 PowerShell 进程。
Get-Content .env.local |
  Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
  ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
  }

Set-Location backend-parent
.\mvnw.cmd verify
.\mvnw.cmd -pl portfolio-server spring-boot:run
```

在 IntelliJ IDEA 中启动时，也要选择 `dev` profile，并把同一 `.env.local` 的键值配置到 Run Configuration；不要只启动数据库而漏掉 Spring 环境。macOS/Linux 使用 `./mvnw`，并在启动前以等价方式导出 `.env.local`。

开发环境变量只应来自未跟踪的本地文件。不要将数据库口令、COS 密钥、SMTP 密钥、TOTP 主密钥或真实访客数据提交到仓库。

## 测试与发布

仓库包含前端单元/E2E 测试、后端测试以及部署契约测试。生产发布不是把工作目录直接复制到服务器：它从一个受保护 Git 提交构建单一、可校验的发布包，再经预检、备份、健康检查和冒烟测试切换版本。

运维入口：

- [生产运行手册](docs/operations/production-runbook.md)
- [备份与恢复手册](docs/operations/backup-recovery.md)
- [发布证据模板](docs/operations/release-evidence-template.md)
- [媒体存储说明](docs/operations/media-storage.md)
- [留言与统计说明](docs/operations/contact-analytics.md)

当前域名为 `yychainsaw.xyz`。在 ICP 最终批准、备案号被记录、DNS 与证书证据齐全之前，公网切换保持关闭；本地部署与回环冒烟不受影响。

## 安全边界

- 生产数据库没有公网端口，运维脚本不得执行未限定目标的 Docker 或文件删除命令。
- 备份必须在应用之外独立运行，使用 `age` 加密并写入异地私有对象存储；解密身份不放在服务器上。
- 恢复演练只能运行在独立 Compose 项目、独立卷和独立 COS 演练前缀中，不能挂载生产目录。
- 管理员恢复会先创建并校验数据库恢复点，再重置密码、TOTP、恢复码和全部会话。
- 所有发布/演练证据都必须脱敏；禁止记录密钥、访客邮箱、私网地址、TOTP URI 或恢复码。

## License

源代码的许可与作品内容、图片、视频、商标和个人资料的使用权是两件事。除非仓库另行添加明确许可证，否则保留全部权利；第三方素材仍受其原始许可约束。
