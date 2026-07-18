# 作品集生产运行手册

适用环境：Ubuntu 22.04、Docker Engine 26+、Docker Compose v2、宝塔面板 Nginx、`yychainsaw.xyz`。本手册是操作清单，不保存任何真实密钥或访客数据。

## 1. 操作原则

每次变更先复制 [发布证据模板](release-evidence-template.md) 建立一份新的、脱敏的记录。执行者必须在记录中写明变更窗口、发布 ID、Git 提交、服务器辖区、腾讯云地域、ICP 状态和回退目标。

以下条件任一不满足时停止公网切换：

- 无法证明目标是这台生产主机、这个 Compose 项目和 `/opt/portfolio`；
- 当前数据库不健康、最近的独立备份未通过只读验证或恢复点超出 24 小时；
- 发布包、分离 envelope、`release.json`、镜像 ID 或源码连续性标签不一致；
- 本地 API、回环 Nginx 或安全预检失败；
- 中国大陆服务器尚未取得 ICP 最终批准和备案号，或 DNS/TLS 证据不完整；
- 需要把密钥写进命令行、日志、工单、Git 或发布证据才能继续。

Docker 组等同于宿主机 root 权限。只有专用部署账号可进入 `portfolio-deploy`/Docker 权限边界，日常内容管理员不获得 SSH、Docker 或数据库权限。

## 2. 资产和责任边界

| 资产 | 生产所有者 | 允许的入口 |
|---|---|---|
| 公网 `80/443`、TLS、站点 include | 宝塔 Nginx | 宝塔安装的同一 Nginx 二进制及配置前缀 |
| API | Docker Compose | 仅 `127.0.0.1:18080` |
| PostgreSQL 17 | Docker Compose | 仅 Compose 私有网络，无宿主端口 |
| 公共 hash 资源 | 宿主机 | `/opt/portfolio/assets`，Nginx 只读 |
| 管理端 | 原子符号链接 | `/opt/portfolio/current-admin` |
| Local 媒体 | 命名卷 | 容器内 `/var/lib/portfolio/media` |
| COS 媒体 | 腾讯云私有桶 | 运行时最小权限身份 |
| 发布配置 | root 管理 | `/etc/portfolio/*.env` |
| 备份 | 独立 systemd 服务 | 异地私有、版本化对象存储 |

生产 Compose 只运行 PostgreSQL 与 API，不启动第二个公网 Nginx。不要在宝塔和 Docker 中各维护一套公网 TLS 终点。

## 3. 首次主机基线

### 3.1 身份、时间和补丁

1. 创建无口令、不可交互或受 SSH 公钥限制的专用部署账号；建立 `portfolio-deploy` 组，只授予发布脚本所需目录与 Docker 权限。
2. 禁止 root 密码 SSH；仅开放密钥登录，限制安全组和主机防火墙的 SSH 来源。
3. 启用自动安全更新，但内核、Docker、PostgreSQL 大版本升级进入维护窗口并先做恢复演练。
4. 启用 `systemd-timesyncd` 或等价 NTP。`timedatectl` 必须显示时钟已同步；备份、TOTP、证书和审计都依赖准确时间。
5. 记录 `docker version`、`docker compose version`、Ubuntu 补丁级别和宝塔 Nginx 绝对路径；Docker Server 主版本必须不低于 26。

### 3.2 网络

云安全组和主机防火墙仅允许：

- 公网 TCP 80/443；
- 来自固定管理源的 SSH；
- 必要的出站 DNS、NTP、SMTP、腾讯 COS、备份远端和系统更新。

拒绝公网访问 18080、5432、Docker API 和宝塔内部管理端口。运行 `ss -ltnp` 确认 18080 只绑定 `127.0.0.1`，5432 没有宿主监听；确认宝塔同一 Nginx PID 拥有 80/443。

### 3.3 目录和权限

建立并核对以下边界：

```text
/opt/portfolio/                  root:portfolio-deploy 0711（仅允许穿越，不允许列目录）
  quarantine/                    root:root             0711
    drop/                        portfolio-upload:portfolio-upload 0700
    verify/                      root:root             0700
  incoming/                      root:root             0750
  releases/                      root:portfolio-deploy 0755
    RELEASE_ID/                  root:portfolio-deploy 0755
      admin/                     root:portfolio-deploy 0755 dirs / 0644 files
      ops/, images/              root:root             0700 dirs / 0600 files
  assets/                        root:portfolio-deploy 0755
  current-admin -> releases/.../admin
  current-ops   -> releases/.../ops
/etc/portfolio/                  root:root 0750
  postgres.env                   root:root 0600
  portfolio.env                  root:root 0600
  backup.env                     root:root 0600
  release.env                    root:portfolio-deploy 0640
  nginx.env                      root:portfolio-deploy 0640
/var/backups/portfolio/staging/  root:root 0700
/usr/local/libexec/portfolio/    root:root 0755（可信 bootstrap 工具，文件不可组写/全局写）
/run/lock/portfolio/             root:root 0700（tmpfiles 每次开机重建）
  deploy.lock                    root:root 0600
  local-volume.lock              root:root 0600
```

除 `current-admin` 与 `current-ops` 两个受控指针外，拒绝这些路径为符号链接。`portfolio-upload` 是没有 Docker、sudo、`portfolio-deploy` 组或交互 shell 的独立传输账号；它只能穿越 `quarantine/` 并读写自己的 `drop/`，不能读取或写入 `verify/`、`incoming/`、`releases/`。发布和恢复脚本只接受解析后仍位于上述根目录内的目标。

`current-ops` 是唯一稳定的“当前版本运维树”入口；不存在 `/opt/portfolio/ops`。安装未来发布不得改变它。只有成功发布或回滚在同一个部署锁内将它切到与 `current-release` 完全一致的 `releases/{id}/ops`，失败恢复必须切回原目标。

### 3.4 初始化 Local 媒体命名卷

生产 API 固定以 `10001:10001` 运行，而新建 Docker 卷通常是 `root:root 0755`。首次启动 API 前必须由 root 使用初始化器创建并验证卷；禁止手工猜测 Docker data-root、直接编辑 marker，或把卷 ID 写进命令行和日志：

```bash
sudo /usr/local/libexec/portfolio/provision-local-volume.sh
```

初始化器只接受 Docker 返回且带 `portfolio.volume-role=local-media` 标签的精确 `portfolio-local-media` 卷，将卷根设置为 `10001:10001 0700`，原子写入 `10001:10001 0600` 的 `.portfolio-volume-id`，并原子同步 `/etc/portfolio/portfolio.env` 中的卷名、宿主 Mountpoint 和 opaque ID。它还会以 UID/GID 10001 做一次创建/删除探针；命令只报告成功，不输出 ID。

如需在迁移时保留一个已离线生成的显式 ID，只能从交互式标准输入提供，不能放在 argv、shell history 或环境变量：

```bash
sudo /usr/local/libexec/portfolio/provision-local-volume.sh --volume-id-stdin
```

初始化后先运行私网 `preflight.sh`。只有 marker 与受保护值一致、卷根和 marker 权限正确、Compose 的唯一读写挂载来源与 Docker Mountpoint 完全一致时，才允许启动 API。不要对包含既有媒体的卷递归 `chown`；本初始化器只校正卷根和身份 marker，既有对象迁移必须走单独审计流程。

## 4. 辖区、ICP、DNS 与 TLS

在证据记录中保存腾讯云控制台显示的服务器地域和法域结论，只写地域代号/辖区，不截图或复制账号信息。当前预期是 `TENCENT_REGION=ap-guangzhou`、`SERVER_JURISDICTION=MAINLAND_CN`；必须由实际控制台证据确认，不能凭模板猜测。

大陆公网切换顺序：

1. 保持 `ICP_APPROVED=false`、`PUBLIC_DOMAIN_ENABLED=false`，完成回环部署与测试。
2. ICP 最终通过后，把备案号写入运维记录和受保护的 `nginx.env`；记录批准时间与查询证据的位置。
3. 先降低 DNS TTL，记录旧 A/AAAA 值；证书必须覆盖 `yychainsaw.xyz` 和实际启用的别名。
4. 渲染配置，使用宝塔实际 Nginx 的 `-p`、`-c` 参数做语法检查；不要调用系统中另一套 `nginx`。
5. 将 A/AAAA 改到证据中确认的公网地址。只有 `preflight.sh --public-cutover` 通过后才启用公共域名并执行公网冒烟。
6. 验证权威 DNS、证书链/主机名/有效期、安全响应头、HTTP 到 HTTPS 跳转和备案展示。

DNS 回退是恢复旧 A/AAAA，并将 `PUBLIC_DOMAIN_ENABLED=false`。内容回退使用发布回滚脚本；不要通过删除数据库或反向迁移来回退。证书续期后先验证私钥权限、证书与私钥公钥匹配、剩余有效期，再由同一宝塔 Nginx 语法检查和 reload。

## 5. 密钥和外部服务

### 5.1 创建与保存

以受限 `umask 077` 直接写入最终 root-only 文件；生成过程不得把值输出到终端历史。数据库口令至少 32 个随机字节，应用 HMAC/TOTP 加密材料至少 32 个随机字节。`deploy/.env.example` 只是键名清单，绝不能在仓库副本上填真实值。

离线保存并定期演练恢复：

- `age` 解密身份；
- TOTP 主密钥环和旧版本密钥；
- 管理员一次性恢复码；
- 域名、腾讯云和备份远端的账号恢复材料。

服务器只保存 `age` 公共 recipient，不保存解密身份。备份密钥丢失意味着所有对应密文永久不可恢复；TOTP 旧密钥过早删除会使现有管理员密文不可解。

### 5.2 最小权限身份

分别创建并记录身份 ID，而非密钥值：

- COS 运行时：仅生产媒体前缀的必要读/写，不含桶生命周期管理；
- COS 生命周期安装：短期、单次写策略，完成后撤销；
- COS 生命周期预检：独立只读身份，每次预检临时提供；
- SMTP：仅指定发件身份，不能管理邮箱；
- 媒体源读取、备份上传、备份只读验证、备份清理：四个不同 rclone principal；
- 恢复演练：短期非生产 COS 身份，演练结束即撤销。

启用 COS 桶版本控制和服务端加密。若供应商支持 Object Lock/WORM，应在建桶时启用并记录模式/期限；它不能替代可达性清理检查。

`nginx.env` 的 `MEDIA_ORIGIN` 必须精确写成公开媒体 302 最终到达的腾讯官方源：`https://<COS_BUCKET>.cos.<COS_REGION>.myqcloud.com`，不能填写尚未由后端签名器使用的自定义 CDN/媒体域名。`VIDEO_FRAME_ORIGINS` 必须与发布映射器实际输出一致：`https://www.youtube.com`、`https://player.vimeo.com`、`https://player.bilibili.com`；漏项会被 CSP 拦截，额外域名则会无必要地扩大浏览器信任边界。每次桶、区域或视频提供商变更都要先通过 Nginx 渲染合同和真实浏览器媒体检查。

### 5.3 Local 命名卷与临时空间

创建外部命名卷 `portfolio-local-media`。在卷根中以 no-follow、owner-only 方式写入随机、不透明的 `.portfolio-volume-id`，同一值保存在受保护配置中。预检必须同时证明：Docker 卷名/挂载点正确、运行中 API 以读写方式挂载到 `/var/lib/portfolio/media`、标记完全匹配、文件系统持久且不是 tmpfs。

轮换卷身份前先取得并验证完整备份，停止媒体写入，核对卷路径，原子替换标记，再预检和恢复写入。标记不匹配时不要“修成能启动”；先判断是否挂错卷。

JVM `/tmp` 和 COS scratch 使用 Compose 的受限 tmpfs，容量、权限和挂载选项由契约测试约束；持久 Local 媒体绝不能落在 tmpfs。启用工作器与 staging cleanup，并保留最近一次当前发布、香港时区 04:00 边界任务成功的证据。

## 6. 数据库首次启动与管理员初始化

1. 安装 `postgres.env`、`portfolio.env`、`release.env`，验证 root 所有权和模式。
2. 从发布包加载并校验记录的 PostgreSQL 17 镜像 ID；启动 `postgres`，等待 Compose health 通过。
3. 启动 API。Flyway 必须由 migrator 角色执行；运行时角色只获得所需 DML，不获得 DDL、所有者或迁移权限。
4. 检查日志中 Flyway 校验成功、应用 readiness 为 `UP`；日志不得包含 JDBC 口令或环境内容。
5. 通过本地主机交互终端启动一次性 `admin-bootstrap`。用户名和密码由交互提示读取，不得放在参数、环境、脚本或会话录制中。
6. 在认证器中扫描 TOTP URI，输入当期验证码完成提交。将显示的十个恢复码离线保存并立即清除终端/剪贴板痕迹。
7. 从 `/admin/` 登录，验证 TOTP、CSRF、退出和会话撤销；不在浏览器密码库之外保存明文。

初始化只能在没有管理员时成功。不要通过直接 SQL 创建或修改管理员。

## 7. 构建、安装与发布

### 7.1 源码连续性

发布输入必须是已推送到受保护私有远端的完整干净提交。构建器不会创建或修改远端标签；操作者先让第一次构建在标签门 fail closed，取得确定的 release ID，再创建 `refs/tags/portfolio-release/{releaseId}`，最后重跑构建并证明远端标签解析到 `release.json.gitCommit`。仓库必须为 private，标签规则必须预先禁止移动和删除；仓库本身不包含生产 secret。

若所有发布包均丢失，只能在可信构建机检出该精确标签，使用 `release.json.buildInputs` 中的不可变 Node/Playwright/JDK/JRE/PostgreSQL digest、APT snapshot、平台和 `sourceDateEpoch` 做 exact-tag rebuild；其中 Playwright 镜像自带浏览器并以断网 E2E 门禁执行，不允许运行时下载浏览器。新产物的完整 `release.json`、JAR/树/镜像/归档摘要必须逐字段一致。

`deploy/image-lock.env` 与 Dockerfile 的默认构建参数必须保存完整的 `tag@sha256`，Dockerfile frontend 也必须固定 digest。构建器禁止从 tag-only 引用首次解析并信任一个新摘要；恢复重建记录中的任一镜像引用只要与当前受保护提交中的锁不完全相等，就必须停止。更新镜像只能通过单独评审提交完成。

### 7.2 构建与传输

在干净、可信的 Linux/amd64 构建器上运行发布构建和打包脚本。构建完成后保存：

构建器必须提供 Java 17 与可用的宿主 Docker daemon。完整 Maven `verify`（包括 Testcontainers/PostgreSQL 集成测试）在受控宿主 Java 17 进程执行；禁止把 Docker socket 挂入 Maven 或合规构建容器。Syft 仅由宿主启动固定摘要、断网、只读、无 capability 的单用途容器，产生的原始 SBOM 以只读文件交给无 Docker 客户端的合规容器。

```bash
cd /trusted/build/personal-portfolio
export SOURCE_CONTINUITY_REMOTE=origin
git status --porcelain=v1 --untracked-files=all   # 必须无输出
git push "$SOURCE_CONTINUITY_REMOTE" HEAD

# 第一阶段必须只在缺少 protected source tag 的门失败；从明确错误中人工记录 RELEASE_ID。
./deploy/scripts/build-release.sh
RELEASE_ID='<12hex-12hex-from-the-tag-gate>'
GIT_COMMIT="$(git rev-parse --verify 'HEAD^{commit}')"

# 先确认私有仓库与 refs/tags/portfolio-release/* 的禁止更新/删除规则已经生效，再创建标签。
git tag -a "portfolio-release/$RELEASE_ID" "$GIT_COMMIT" \
  -m "Portfolio release $RELEASE_ID"
git push "$SOURCE_CONTINUITY_REMOTE" \
  "refs/tags/portfolio-release/$RELEASE_ID"
git ls-remote --exit-code --tags "$SOURCE_CONTINUITY_REMOTE" \
  "refs/tags/portfolio-release/$RELEASE_ID" \
  "refs/tags/portfolio-release/$RELEASE_ID^{}"

# 第二阶段必须从同一 clean HEAD 完成；返回值必须正好等于第一阶段记录的 ID。
BUILT_RELEASE_ID="$(./deploy/scripts/build-release.sh)"
test "$BUILT_RELEASE_ID" = "$RELEASE_ID"
./deploy/scripts/package-release.sh "$RELEASE_ID"
```

- 发布 ID、Git 提交和受保护标签；
- 外层 `.tar.zst` 的 SHA-256 与字节数；
- 分离 envelope 的 SHA-256；
- `release.json` SHA-256；
- API/PostgreSQL 镜像 ID；
- 所有契约测试结果。
- `portfolio-bootstrap-{gitCommit}.tar.zst`、其精确字节数/SHA-256、内部 manifest SHA-256 和完整 40 位 Git commit；
- 独立的 `install-bootstrap-kit-{gitCommit}.py` 及其精确 SHA-256 与字节数（必须和 kit 值一起进入独立认证记录）。

`package-release.sh` 同时生成发布包对、以完整 Git commit 命名的最小 bootstrap kit，以及同目录的 standalone `install-bootstrap-kit-{gitCommit}.py`。kit 包含该安装器、发布安装器、tar validator、上传提升器和 Local 卷初始化器；内部 `bootstrap-manifest.json` 逐文件绑定路径、模式与 SHA-256。kit envelope 与内部 manifest 只是自洽校验，**不能充当信任根**。操作者必须从与制品下载相互独立、已认证的 CI 发布记录或签名运维记录取得完整 40 位 commit、kit SHA-256/字节数、standalone installer SHA-256/字节数；不得从刚上传的 envelope/manifest 反推任何“期望值”。

通过受认证 SSH/rsync 或私有制品仓，以 `portfolio-upload` 身份只上传到 `/opt/portfolio/quarantine/drop`。普通发布只上传发布包与发布 envelope 两个文件；干净主机 bootstrap 则上传 kit、kit envelope、standalone installer 三个文件。每个文件都使用不可预测的临时名（例如 `.portfolio-bundle.part.<16-64位随机字母数字>`），模式固定为 `0600`；上传账号永远不写 `/opt/portfolio/incoming`，也不得把半传输文件改成最终名。

### 7.3 首次 bootstrap（仅干净主机）

首次安装没有任何仓库脚本可供信任。在读取或执行上传的 kit 之前，先通过 Ubuntu 已配置且经过签名验证的 APT 源安装宿主工具。不得使用 `curl | sh`、远程 shell、未固定的第三方安装脚本或把上传制品当作工具来源。Docker Engine 26+ 与 Compose v2 仍按 3.1 和发布前 `preflight.sh` 的现有版本门安装、记录和复核；不要在宿主安装 PostgreSQL client，`pg_dump`/`pg_restore` 由受发布镜像约束的生产 PostgreSQL 容器经 dispatcher 提供。

在干净 Ubuntu 22.04 主机以 root 执行以下先决条件门。`coreutils` 提供 `stat`/`sha256sum`/`install`/`mv`/`mktemp`，`util-linux` 提供 `flock`/`setpriv`，`uuid-runtime` 提供 `uuidgen`；其余命令由同名包提供。`tar` 是发布包安装器的解包依赖：

```bash
sudo -i
set -euo pipefail
set +x
umask 077
export PATH=/usr/sbin:/usr/bin:/sbin:/bin

. /etc/os-release
[[ "$ID" == ubuntu && "$VERSION_ID" == 22.04 ]]

APT_PACKAGES=(
  ca-certificates curl python3 zstd jq coreutils util-linux tar openssl
  rclone age uuid-runtime msmtp-mta
)
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  "${APT_PACKAGES[@]}"

# 先证明所有包均由 dpkg 完整登记，再核对将被后续信任门调用的实际二进制。
dpkg-query -W -f='${db:Status-Abbrev} ${binary:Package}\t${Version}\t${Architecture}\n' \
  "${APT_PACKAGES[@]}" | awk '$1 != "ii" { exit 1 }'
hash -r
for command_name in \
  update-ca-certificates curl python3 zstd jq stat sha256sum install mv mktemp \
  flock setpriv tar openssl rclone age uuidgen msmtp
do
  command_path="$(command -v -- "$command_name")"
  case "$command_path" in
    /usr/bin/*|/usr/sbin/*|/bin/*|/sbin/*) ;;
    *) printf 'untrusted command path: %s -> %s\n' "$command_name" "$command_path" >&2; exit 1 ;;
  esac
  resolved_path="$(readlink -e -- "$command_path")"
  [[ -n "$resolved_path" && -f "$resolved_path" && -x "$resolved_path" ]]
  [[ "$(stat -Lc '%u' -- "$resolved_path")" == 0 ]]
  binary_mode="$(stat -Lc '%a' -- "$resolved_path")"
  (( (8#$binary_mode & 8#022) == 0 ))
done

docker_server_version="$(docker version --format '{{.Server.Version}}')"
docker_server_major="${docker_server_version%%.*}"
[[ "$docker_server_major" =~ ^[0-9]+$ && "$docker_server_major" -ge 26 ]]
docker compose version
```

把 `/etc/os-release` 的补丁标识、`apt-get indextargets` 返回的已签名源 host/suite/component/architecture 摘要、上述包的 `dpkg-query` 精确版本/架构、二进制门结果，以及 Docker Server/Compose 版本写入受控发布证据记录。若 APT URL 含凭据，只记录脱敏的 host、suite、签名 key fingerprint 和内部证据 ID；禁止记录 URL 凭据或环境内容。任一包缺失、源身份不明、命令未解析到系统目录、解析后文件不属于 root、可被 group/world 写入，或 Docker/Compose 版本门失败时，必须在读取 kit 之前停止。

先决条件门通过后，把 kit、kit envelope 和 standalone installer 以三个随机 `.part` 名上传；然后 root 使用下面的完整流程。五个 `EXPECTED_*` 值（commit、kit SHA/bytes、installer SHA/bytes）必须在执行前从独立认证记录人工输入，不能用 `jq` 从上传 envelope 填充：

```bash
sudo -i
set -euo pipefail
set +x
umask 077

read -r -p 'Trusted 40-char Git commit: ' EXPECTED_GIT_COMMIT
read -r -p 'Trusted kit SHA-256: ' EXPECTED_KIT_SHA256
read -r -p 'Trusted kit bytes: ' EXPECTED_KIT_BYTES
read -r -p 'Trusted standalone installer SHA-256: ' EXPECTED_INSTALLER_SHA256
read -r -p 'Trusted standalone installer bytes: ' EXPECTED_INSTALLER_BYTES

[[ "$EXPECTED_GIT_COMMIT" =~ ^[0-9a-f]{40}$ ]]
[[ "$EXPECTED_KIT_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$EXPECTED_INSTALLER_SHA256" =~ ^[0-9a-f]{64}$ ]]
[[ "$EXPECTED_KIT_BYTES" =~ ^[1-9][0-9]*$ ]]
[[ "$EXPECTED_INSTALLER_BYTES" =~ ^[1-9][0-9]*$ ]]

# 只改这三个随机临时名；不要改为最终名。
KIT_PART=/opt/portfolio/quarantine/drop/.portfolio-bootstrap.part.REPLACE16RANDOM
ENVELOPE_PART=/opt/portfolio/quarantine/drop/.portfolio-bootstrap-envelope.part.REPLACE16RANDOM
INSTALLER_PART=/opt/portfolio/quarantine/drop/.portfolio-bootstrap-installer.part.REPLACE16RANDOM
UPLOAD_UID="$(id -u portfolio-upload)"

for source in "$KIT_PART" "$ENVELOPE_PART" "$INSTALLER_PART"; do
  [[ "$source" == /opt/portfolio/quarantine/drop/.*.part.* ]]
  [[ -f "$source" && ! -L "$source" ]]
  [[ "$(stat -Lc '%u:%a:%h' -- "$source")" == "$UPLOAD_UID:600:1" ]]
done
[[ "$(stat -Lc '%u:%g:%a' /opt/portfolio/quarantine)" == 0:0:711 ]]
[[ "$(stat -Lc '%u:%a' /opt/portfolio/quarantine/verify)" == 0:700 ]]
[[ "$(stat -Lc '%d' /opt/portfolio/quarantine/drop)" == \
   "$(stat -Lc '%d' /opt/portfolio/quarantine/verify)" ]]

VERIFY_DIR="$(mktemp -d /opt/portfolio/quarantine/verify/.bootstrap.XXXXXXXX)"
mv -T -- "$KIT_PART" "$VERIFY_DIR/kit.upload"
mv -T -- "$ENVELOPE_PART" "$VERIFY_DIR/envelope.upload"
mv -T -- "$INSTALLER_PART" "$VERIFY_DIR/installer.upload"
for source in "$VERIFY_DIR"/*.upload; do
  [[ -f "$source" && ! -L "$source" && "$(stat -Lc '%u:%a:%h' "$source")" == "$UPLOAD_UID:600:1" ]]
done

TRUSTED_KIT="$VERIFY_DIR/portfolio-bootstrap-$EXPECTED_GIT_COMMIT.tar.zst"
install -o root -g root -m 0600 "$VERIFY_DIR/kit.upload" "$TRUSTED_KIT"
install -o root -g root -m 0600 "$VERIFY_DIR/envelope.upload" "$VERIFY_DIR/kit.envelope.json"
TRUSTED_INSTALLER="$VERIFY_DIR/install-bootstrap-kit-$EXPECTED_GIT_COMMIT.py"
install -o root -g root -m 0600 "$VERIFY_DIR/installer.upload" "$TRUSTED_INSTALLER"

# 这是 Python 执行前的独立信任门，必须保留；envelope 自洽检查不能替代它。
[[ "$(stat -Lc '%s' "$TRUSTED_KIT")" == "$EXPECTED_KIT_BYTES" ]]
[[ "$(stat -Lc '%s' "$TRUSTED_INSTALLER")" == "$EXPECTED_INSTALLER_BYTES" ]]
printf '%s  %s\n' "$EXPECTED_KIT_SHA256" "$TRUSTED_KIT" | sha256sum -c -
printf '%s  %s\n' "$EXPECTED_INSTALLER_SHA256" "$TRUSTED_INSTALLER" | sha256sum -c -

python3 "$TRUSTED_INSTALLER" \
  --kit "$TRUSTED_KIT" \
  --envelope "$VERIFY_DIR/kit.envelope.json" \
  --expected-git-commit "$EXPECTED_GIT_COMMIT" \
  --expected-kit-sha256 "$EXPECTED_KIT_SHA256" \
  --expected-kit-bytes "$EXPECTED_KIT_BYTES" \
  --expected-installer-sha256 "$EXPECTED_INSTALLER_SHA256" \
  --expected-installer-bytes "$EXPECTED_INSTALLER_BYTES"
```

standalone installer 自身再次核对同一组独立值。它用原子 `mkdir` 创建并严格验证 `root:root 0700` 的 `/run/lock/portfolio`（若攻击者抢先放入文件、目录或链接则拒绝，不会 chown/跟随），取得共享部署锁，用 `zstd` 只解压到 root-only 临时 tar，随后由 Python `tarfile` 在**写出任何 kit 成员前**验证精确 entry 集、类型、路径、PAX/链接/设备/重复、uid/gid/mtime、manifest commit/模式/SHA。它还原子安装精确的 `/etc/tmpfiles.d/portfolio.conf`，内容为 `d /run/lock/portfolio 0700 root root -`，确保 `/run` 在每次重启清空后由 systemd-tmpfiles 重建；已有不同配置即失败。最后逐文件写入 root-owned 同父目录候选并 fsync，用 Linux `renameat2(RENAME_NOREPLACE)` 原子、create-only 安装 `/usr/local/libexec/portfolio`；目标已存在即失败。

以上命令成功后再删除 `VERIFY_DIR`；失败时不要执行上传内容中的其他程序，保留 root-only 候选供调查。

安全性质说明：

1. 从独立认证通道读取 `EXPECTED_GIT_COMMIT`、`EXPECTED_KIT_SHA256`、`EXPECTED_KIT_BYTES`、`EXPECTED_INSTALLER_SHA256`、`EXPECTED_INSTALLER_BYTES` 并核对格式；这些值不得来自 drop 中的 envelope 或 manifest。
2. root 对 drop 文件使用 no-follow 检查：父目录必须是预期的 `portfolio-upload` UID、`0700`、非链接；文件必须是该 UID 所有的普通非链接文件、`0600`、硬链接数 1，且名字仍为 `.part.<随机值>`。
3. drop 与 `quarantine/verify` 必须在同一文件系统。root 将 kit 与 envelope 用 `mv -T` 原子移入 root-only `verify/` 的新随机目录，再用 `install -o root -g root -m 0600` 复制成 root-owned 候选。只对这个候选核对独立字节数和 `sha256sum`；不匹配立即停止，保留脱敏证据，不解压。
4. 外部摘要匹配后，standalone installer 校验 kit envelope 的精确键集、commit、archive/installer 名、SHA 和字节数；在落地任何成员前完成 tar/manifest 全量校验。
5. `/usr/local/libexec/portfolio` 必须尚不存在。禁止覆盖已有目录；已有安装必须先单独核验，不得用上传内容就地更新。

完成后稳定工具为：

```text
/usr/local/libexec/portfolio/install-release-bundle.sh
/usr/local/libexec/portfolio/install-backup-units.sh
/usr/local/libexec/portfolio/install-bootstrap-kit.py
/usr/local/libexec/portfolio/promote-release-upload.sh
/usr/local/libexec/portfolio/provision-local-volume.sh
/usr/local/libexec/portfolio/validate-bundle-tar.py
```

### 7.3.1 首个发布完成后的备份 unit 闭包

干净主机第一次运行目标发布绝对路径下的 `deploy-release.sh RELEASE_ID --initial-empty-database` 时，数据库在发布前不存在，因此只允许这一次明确跳过发布前备份门。该次发布成功后，先确认媒体源读取、备份上传、备份只读验证、备份清理四类独立 principal 及其 root-only 配置均已落地，`/etc/portfolio/backup.env` 和受审计 prune guard 已就绪；然后在把站点视为可交付之前、并且绝对要在第二次发布之前，按以下顺序安装并验证定时备份闭包。升级时也必须完整重做该门禁。

首先确认 `/opt/portfolio` 是 canonical、root-owned、精确 `0711`（保留已经审核的 group），且 `/run/lock/portfolio` 是 `root:root 0700`。随后在共享部署锁内停用旧 prune timer、验证它不再运行，并从锁定的当前发布安装 hash-locked Python runtime：

```bash
sudo bash -ceu '
  exec 9<>/run/lock/portfolio/deploy.lock
  flock -x 9
  load="$(systemctl show portfolio-backup-prune.timer --property=LoadState --value)"
  if [[ "$load" == loaded ]]; then
    systemctl disable --now portfolio-backup-prune.timer
    [[ "$(systemctl show portfolio-backup-prune.timer --property=ActiveState --value)" == inactive ]]
    [[ "$(systemctl is-enabled portfolio-backup-prune.timer 2>/dev/null || true)" == disabled ]]
  else
    [[ "$load" == not-found ]]
    [[ "$(systemctl show portfolio-backup-prune.timer --property=ActiveState --value)" == inactive ]]
  fi
  release_id="$(tr -d "\r\n" </opt/portfolio/current-release)"
  [[ "$release_id" =~ ^[0-9a-f]{12}-[0-9a-f]{12}$ ]]
  release_root="$(realpath -e -- "/opt/portfolio/releases/$release_id")"
  [[ "$release_root" == "/opt/portfolio/releases/$release_id" ]]
  bash "$release_root/ops/deploy/backup/install-cos-prune-runtime.sh"
  runtime_state="$(/opt/portfolio/cos-prune-venv/bin/python3 -B \
    "$release_root/ops/deploy/backup/cos-prune-guard.py" runtime-check)"
  [[ "$runtime_state" == SAFE ]]
'
```

`install-cos-prune-runtime.sh` 不接受参数；当前 reviewed lock 的成功末行必须是 `PASS: installed hash-locked COS prune runtime 96d0cf7823a4f32f2fbb97e94919882827fd8aa9cd153f8395ef26459a19ff4a`。lock 更新时必须以该发布内 `requirements-cos-prune.txt` 的独立审核 SHA-256 替换记录值，不能沿用本手册中的旧摘要。脚本固定安装 `/opt/portfolio/cos-prune-venv`，并依次持有 runtime installer lock 和 `/var/backups/portfolio/operation.lock`；任何失败都保持 prune timer 停用。

runtime-check 精确返回 `SAFE` 后，再运行 unit 安装器。安装器会重新取得部署锁，在读取任何可变 release 状态之前再次停用并复核旧 prune timer；随后运行不启用的 `portfolio-backup-prune-readiness.service`，由同一 sandbox 和 `EnvironmentFile` 调用真实 `prune-remote.sh --dry-run`。它会先安全删除旧 readiness marker 并 fsync 父目录，因此本次 service 必须生成全新证据：

```bash
sudo /usr/local/libexec/portfolio/install-backup-units.sh --run-initial-backup
sudo systemctl is-enabled portfolio-backup.timer portfolio-backup-prune.timer
sudo systemctl is-active portfolio-backup.timer portfolio-backup-prune.timer
sudo systemctl show \
  portfolio-backup.service portfolio-backup-prune.service \
  portfolio-backup-prune-readiness.service \
  portfolio-backup.timer portfolio-backup-prune.timer \
  --property=LoadState --property=FragmentPath --property=DropInPaths \
  --property=NeedDaemonReload --property=ActiveState --property=SubState \
  --property=UnitFileState --property=Result --property=ExecMainStatus
```

readiness 证据默认是 `/var/backups/portfolio/prune-initial-dry-run.json`；只有 `backup.env` 中经过审核的 `BACKUP_PRUNE_READINESS_FILE` 才能覆盖。它必须是私有 root-owned single-link `0600` JSON，精确绑定当前 destination、guard/requirements/wrapper SHA-256、7/4/6 与 safetyDays 策略，并带秒级 UTC `completedAt`。安装器要求 runtime-check、readiness service 的 `Result=success`/`ExecMainStatus=0`、全新证据 shape 与摘要全部通过后，才执行 `enable --now portfolio-backup-prune.timer`；部分 enable 后失败也会在 EXIT 清理中再次停用。任何失败都不得手工启用 prune timer。

安装器要求稳定 dispatcher 与当前 release-local dispatcher 字节完全相同，要求 `/etc/portfolio/backup.env` 为 `root:root 0600`，并把当前发布中的五个 unit 以精确字节、`root:root 0644` 安装到 `/etc/systemd/system`；已有文件只能字节相同，禁止覆盖漂移。所有 unit 的有效 `FragmentPath` 必须正好位于 `/etc/systemd/system`、`DropInPaths` 为空、`NeedDaemonReload=no`，两个 timer 最终为 `active/waiting`，而 readiness service 不得 enable。`--run-initial-backup` 还必须得到 `Result=success` 和 `ExecMainStatus=0`。任何 `/run/systemd/system` 覆盖、drop-in、缓存未刷新、unit 漂移、runtime/readiness 失败或首次备份失败都必须停止交付；不得手工复制上传目录中的 unit，也不得改用 `/opt/portfolio/ops`。

### 7.4 发布包隔离提升、安装与切换

每个正式发布包先由 root 使用独立记录中的两个 SHA-256/字节数和 release ID 调用稳定提升器：

```bash
sudo /usr/local/libexec/portfolio/promote-release-upload.sh \
  /opt/portfolio/quarantine/drop/.portfolio-bundle.part.RANDOM1234567890 BUNDLE_SHA256 BUNDLE_BYTES \
  /opt/portfolio/quarantine/drop/.portfolio-envelope.part.RANDOM0987654321 ENVELOPE_SHA256 ENVELOPE_BYTES RELEASE_ID
```

提升器取得 `/run/lock/portfolio/deploy.lock` 后，先将两个 uploader-owned、普通、非链接、`0600`、link-count=1 的临时文件原子隔离到 root-only `verify/`，再复制为 root-owned 候选并重新核对独立 SHA/字节数及 envelope 绑定。最终用同目录硬链接 create-only 发布为 `/opt/portfolio/incoming/portfolio-{releaseId}.tar.zst[.envelope.json]`；任一最终名已存在即失败，不覆盖、不沿用符号/硬链接。成功后再调用稳定安装器。

1. 使用 `/usr/local/libexec/portfolio/install-release-bundle.sh`。它在任何 staging、镜像 tag 或 stable dispatcher 变化前取得与发布/回滚/修剪相同的独占锁；严格拒绝非规范、链接、非 root 所有或可写的锁路径。它先流式验证 tar，再解入同文件系统的空 staging；校验 manifest 和两份镜像归档后才原子安装到 `releases/{releaseId}`。安装未来发布不会改变 `current-ops`。
2. 检查 `/etc/portfolio/*.env` 模式、磁盘余量、数据库健康、Docker/Compose 版本、命名卷、COS 生命周期与宝塔配置。
3. 首次运行 `sudo /opt/portfolio/releases/$RELEASE_ID/ops/deploy/scripts/preflight.sh --initial-empty-database`；随后发布使用同一目标 release-local 绝对路径，按需加 `--public-cutover`。
4. 首次运行 `sudo /opt/portfolio/releases/$RELEASE_ID/ops/deploy/scripts/deploy-release.sh "$RELEASE_ID" --initial-empty-database`；以后运行 `sudo /opt/portfolio/releases/$RELEASE_ID/ops/deploy/scripts/deploy-release.sh "$RELEASE_ID" [--public-cutover]`。同一个发布锁会串行化上传提升、安装、发布、回滚和修剪；锁由最外层持有并传给修剪器，禁止用不同锁文件并发。
5. 控制器只通过 `systemctl start portfolio-backup.service` 生成并只读验证数据库+媒体备份集，再用 `systemctl show` 要求 oneshot 的 `Result=success` 与 `ExecMainStatus=0`。服务只加载 root-only `/etc/portfolio/backup.env` 与 `/etc/portfolio/release.env`，使用稳定 dispatcher 解析 `current-release`；不得加载 `portfolio.env`，unit 必须显式清除应用/COS/SMTP/age 解密环境变量。配置缺失、unit 漂移或任何备份失败都在启动目标服务前 fail closed。首次 `--initial-empty-database` 没有可备份数据库，明确跳过该门。
6. 控制器以 create-only 方式安装 hash 资源；同名不同 SHA 必须失败。
7. 切换 release env，启动/迁移 API，等待 readiness，运行 `api-local` 冒烟和当前发布 cleanup 证据门。
8. 原子切换管理端，使用准确宝塔命令 test/reload，运行 `nginx-local` 冒烟。
9. 只有 ICP/DNS/TLS 门通过才运行 `public` 冒烟。
10. 成功后在锁内将 `current-ops` 与 current/previous marker 切到同一发布，保留当前、上一个和最新第三个发布；七天内或仍被三个发布引用的 hash 资源不删除。回滚执行相同的一致性检查和指针切换。

发布记录不得包含 env 内容。只记录命令名、退出码、摘要、时间、允许列表状态和错误类别。

### 7.5 冒烟范围

`api-local` 验证 readiness、公共/管理 API 形状、ETag、JSON 404 和服务端 HTML；`nginx-local` 通过回环 Nginx 验证管理端、hash 资源、代理、媒体字节和安全/缓存头；`public` 使用真实 DNS/TLS 重复边缘矩阵。媒体验证必须比较最终响应体 SHA-256 和 Content-Type，数据库存在记录不能替代字节检查。

## 8. 回滚

回滚前记录当前/目标发布和数据库最近备份。运行 `sudo /opt/portfolio/current-ops/deploy/scripts/rollback-release.sh [releaseId]`；省略 ID 时目标为 previous marker。

回滚只恢复旧 API 镜像、`release.env`、管理端链接和 Nginx 状态，不执行数据库 down migration。前向 Flyway 迁移必须保持向后兼容至少覆盖三个保留发布。回滚自己的健康或冒烟失败时，脚本恢复回滚前状态并退出非零；保留脱敏诊断，不要手工删除 marker 或 release。

若数据库迁移本身破坏兼容性，停止公网流量并进入 [备份与恢复手册](backup-recovery.md) 的事故恢复流程，不能尝试猜测 SQL 逆操作。

## 9. 日常监控

每天查看管理端系统状态和宿主机告警：

- readiness、容器重启和 PostgreSQL health；
- 磁盘/ inode、命名卷余量、Docker 日志增长；
- 最近数据库/媒体备份是否 24 小时内成功并远端验证；
- `job`/email outbox 的失败、dead、重试积压；
- analytics/contact retention/media cleanup 是否按期运行；
- COS 403/5xx、上传 scratch、SMTP 退信；
- 证书剩余期、NTP 同步、系统安全更新；
- 管理员失败登录、会话撤销和异常审计事件。

systemd timer 必须启用并检查上次/下次时间。日志保留受限、轮转，并禁止 `set -x` 和环境 dump。

## 10. 故障处理

### 数据库不可用

冻结发布和媒体清理，保留容器/卷，不执行 `down -v`。检查磁盘、OOM、Compose health 和 PostgreSQL 日志的脱敏类别。能原位恢复时先取得恢复点；不能时使用已验证备份在隔离环境验证后再决定生产恢复。

### 迁移失败

不要切管理端或公网。保留前一 API，记录失败版本和 Flyway 状态。若旧 API 与已提交迁移兼容则回滚应用；否则停止写入并按事故恢复处理。

### COS 或 SMTP 失败

COS 失败时保留数据库/Local staging，不开启物理清理；检查短期身份、地域、桶策略、生命周期和时钟。SMTP 失败由 outbox 重试，留言持久化不能依赖即时邮件成功；修复后受限重放 dead 行。

### 磁盘或日志告警

先停止发布和非必要写入。只通过保留策略清理已证明无引用的发布/资源、已完成 staging 和轮转日志；禁止对 Docker 根或媒体卷运行通配删除。扩容后重新预检。

### 凭据泄露

撤销受影响身份，保留审计证据，生成新身份并最小授权；轮换数据库/COS/SMTP/rclone/TOTP 密钥时逐项验证。若备份读身份泄露，评估密文和离线 age 身份是否同时暴露；若 TOTP 主密钥泄露，强制管理员恢复和会话撤销。完成后执行一次隔离恢复演练。

## 11. 安全管理员恢复

`admin-recover` 只在确认管理员无法通过密码、TOTP 或恢复码进入时使用：

1. 冻结发布并确认数据库 health。
2. 先运行独立备份，且由 verifier 读回 `VERIFIED`；记录 set/checksum。若备份系统不可用，不继续重置。
3. 在本地主机交互终端启动当前受信 API 镜像的 `admin-recover`，不把密码、确认语句或 TOTP 放进命令行/环境。
4. 工具还会创建数据库 restore point；核对其 SHA-256 已写入受保护恢复目录。
5. 输入新强密码、登记新 TOTP、离线保存新恢复码。
6. 验证所有旧会话已撤销、旧恢复码失效、审计存在 `ADMIN_RECOVERED`，再恢复公网管理入口。

禁止直接更新 `admin_user`、删除 Spring Session 表或绕过 TOTP。

## 12. 维护完成条件

一次生产变更只有在以下证据全部存在时完成：目标发布身份一致；备份远端只读验证成功；API/Nginx 冒烟成功；marker 与 env 一致；公网变更时真实 DNS/TLS/ICP 通过；无 secret 扫描命中；失败时已恢复原状态；发布证据经另一位审核者或延迟复核确认。
