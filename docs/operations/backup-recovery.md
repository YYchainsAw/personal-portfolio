# 备份与恢复手册

目标：生产数据恢复点目标（RPO）不超过 24 小时，恢复时间目标（RTO）不超过 4 小时。备份成功的定义是“异地、加密、自包含、由独立只读身份重新验证”，不是本机生成了一个文件。

## 1. 覆盖范围与威胁模型

每个恢复点必须覆盖：

- PostgreSQL 业务 schema、Flyway 历史、管理员/会话、发布快照、媒体引用、留言和聚合数据；
- 所有 `LOCAL` 原件和 variant 的实际字节；即使没有 Local 行也包含合法空 tar；
- 所有 `TENCENT_COS` 原件和 variant 的实际字节，以不可变 `blobs/{sha256}` 保存；
- 加密的权威混合媒体 manifest、自包含非秘密 set manifest 和 `VERIFIED` 标记；
- 对应发布的 API 镜像、管理端/公共资源、Compose/Nginx 配置；若包丢失，使用受保护 Git 标签 exact rebuild；
- 离线保管的 age 身份、TOTP 密钥环和恢复操作资料。

备份远端必须与生产媒体桶分离，私有、TLS-only、版本化、静态加密。可用时在建桶阶段启用 Object Lock/WORM。生产主机没有 age 私钥；勒索、误删、源桶损坏、数据库损坏和单个云身份泄露都不应同时摧毁恢复能力。

证据中永不出现真实 secret、桶密钥、访客邮箱、私网地址、对象 key、TOTP seed、恢复码或 age identity。

## 2. 配置和身份

`/etc/portfolio/backup.env` 必须为 `root:root 0600`，只保存 age 公共 recipient、远端/非根前缀、root-only rclone 配置路径、Local 媒体根、通知目标和 `Asia/Hong_Kong` 时区。它不能包含一个全局“媒体来源模式”；每一行由数据库中的 provider 决定。

四类 rclone 身份必须不同：

| 身份 | 允许 | 禁止 |
|---|---|---|
| 源媒体 reader | 生产源桶 list/read | write/delete/备份桶管理 |
| 备份 uploader | 目标 `sets/`、`blobs/`、`drill-reports/` create/stat | overwrite/delete/object body read |
| verifier | 目标 list/read | put/delete |
| pruner | 读 manifest/marker，写 `gc-reports/`，受限删除候选 | 读 DB/媒体正文、覆盖、根级删除 |

备份服务不挂载 pruner 配置，清理服务不挂载数据库、媒体 reader 或 uploader 配置。独立失败通知使用 root-only msmtp 或云监控通道，即使 API 停止也能告警。

`BACKUP_PREFIX` 必须是非空、非根、无 `..`/反斜杠/控制字符的受审前缀。所有写删只允许解析到其 `sets`、`blobs`、`drill-reports`、`gc-reports` 子树；源和备份账号/桶不得相同。

## 3. 一致性备份流程

生产入口只有 `deploy/backup/backup-set.sh`。夜间 timer 使用不依赖宿主 `tzdata` 的 `18:30 UTC` 表达式运行（对应香港时间次日 02:30），最长三小时；发布前也调用同一入口。远端保留清理使用 `22:30 UTC`（对应香港时间次日 06:30），因此与备份保持四小时的固定间隔。

流程必须保持以下顺序：

1. 在 `/var/backups/portfolio/staging` 下创建 `0700` 临时目录。
2. 启动唯一 keeper 连接，获取 session-level shared advisory lock `(1347375700,1296385097)`。
3. keeper 开始 `REPEATABLE READ READ ONLY` 事务，调用 `pg_export_snapshot()`。
4. 通过受保护文件描述符把同一个 snapshot ID 交给 `pg_dump` 和媒体 manifest reader；后者先以相同隔离级别开始事务再 `SET TRANSACTION SNAPSHOT`。
5. `pg_dump` 使用 PostgreSQL 17 custom format、`--no-owner`。完成后由同版本 `pg_restore --list` 验证，并确认关键表包含 Flyway、publication/revision/media、contact 和 admin。
6. 两个 snapshot 消费者结束后提交 keeper 事务，但继续持有 session shared lock。此时数据库快照关闭，物理媒体删除仍被阻塞。
7. 计算明文 dump SHA-256 和不含 PII 的计数；用 age recipient 加密，随后删除明文。
8. 按每一行的 provider 构建媒体闭包并上传；任何未知 provider、缺失字节、size/hash/MIME 不符都失败关闭。
9. 所有 artifact 就绪后最后上传 canonical `set-manifest.json`；verifier 用独立身份下载并核验完整闭包。
10. uploader 创建不可变 `VERIFIED`，其内容是 set-manifest checksum；verifier 再次读回匹配。
11. 只有远端验证完成后执行 shared unlock，且必须恰好一行返回成功。最后写脱敏 maintenance 结果并清除 staging。

cleanup 使用相同 key 的 session-level exclusive advisory lock，因此 backup 与物理删除不能交错。不要替换为 transaction lock、两个 keeper 或 JVM 锁。

失败时 trap 终止 keeper、清除明文、写 allowlisted 错误类别并独立通知。上传或验证部分成功不构成恢复点，也不能释放后让清理继续。

## 4. 混合媒体闭包

加密权威 manifest 逐行包含：asset/variant ID、provider、bucket、region、object key、MIME、字节数、明文 SHA-256、snapshot/set ID 和数据库 dump 明文 SHA-256；不包含 caption、原始文件名、翻译或访客数据。

### Local

每个 object key 必须 no-follow 解析在 `LOCAL_MEDIA_ROOT` 下，拒绝链接和穿越，逐文件验证 size/SHA。创建 deterministic POSIX ustar，恰好包含快照中的全部 Local 原件/variant；零行时创建合法空 tar。加密前用 `validate-media-tar.py` 完整验证，随后 age 加密和远端密文校验。

### COS

按行使用源 reader 读取并核对 size/SHA，将字节 create-only 写到 `blobs/{plaintext-sha256}`。已存在 blob 只有在 verifier 独立下载并重算 SHA 后才可复用。非秘密 set manifest 不出现源账号、桶、region 或 object key。

COS 样本数是精确公式：

- `total == 0`：0；
- `total < 20`：全部；
- 其他：`min(total, 200, max(20, ceil(total * 0.01)))`。

以 set ID 对 hash 做确定性选样，verifier 下载并重算明文 SHA。Local 明文在制 tar 前全部验证；季度演练对所有需要恢复的 Local/COS 字节做完整验证。

## 5. 自包含 set

远端布局：

```text
{BACKUP_PREFIX}/
  blobs/{plaintext-sha256}
  sets/{setId}/
    database.dump.age
    local-media.tar.age
    media-manifest.json.age
    set-manifest.json
    VERIFIED
  drill-reports/{drillId}/...
  gc-reports/{gcId}/...
```

`set-manifest.json` 是 canonical、非秘密、自包含索引：记录 set/snapshot ID、三个加密 artifact 的路径/密文 hash、计数、daily/weekly/monthly eligibility，以及完整排序的 `blobs/{sha256}` 路径/size/hash。恢复一个 set 不依赖更早 set 或可变索引。

`VERIFIED` 不存在、manifest/marker 不一致、artifact 缺失或只存在上传中标记时，该 set 不可发布、不可保留、不可恢复。

## 6. 保留与垃圾回收

每次运行生成 daily set；配置的每周首日 set 同时 weekly eligible，每月 1 日同时 monthly eligible。保留最新 7 个 daily、4 个 weekly、6 个 monthly 的不同已验证 set。

`prune-remote.sh --dry-run` 必须先执行。pruner：

1. 证明远端账号/桶、版本控制/Object Lock 状态和非根前缀；
2. 校验所有候选/保留 set manifest 与 VERIFIED；
3. 先 create-only 持久化拟删除报告；
4. 从全部保留集合重建 `blobs` 可达性 union；
5. 只删除未保留 set 的本地 artifact；blob 还必须满足无保留引用、超过安全年龄、保护版本到期、路径仍在 `blobs/`；
6. 对具体 version ID 操作，并保留最终 GC 报告。

保留集合为空时默认拒绝清理。灾难重置需要独立、明确的人工确认和已验证离线清单。禁止对 remote/bucket root 使用 `delete`、`purge` 或通配删除。

## 7. 每日核验与故障

每天检查 timer、最近 set 的时间、数据库与媒体 maintenance 状态、远端 VERIFIED checksum、staging 是否无明文、通知是否可达。超过 24 小时无成功 set 立即告警并冻结发布和媒体物理清理。

常见故障处理：

- keeper/snapshot：检查连接与锁等待，不启动第二个并行备份；
- `pg_restore --list`：把 dump 视为不可用，检查 PostgreSQL 17 客户端和源库；
- Local hash：冻结媒体清理，调查卷身份、文件损坏或竞态；
- COS 403：检查短期身份、地域、时钟和策略，不放宽为桶管理员；
- upload 成功、verify 失败：保留部分 set 供调查但不写 VERIFIED；
- 通知失败：使用第二监控通道，不能把 API 邮件当唯一告警；
- staging 留有明文：隔离主机、限制访问、完成取证后安全清除并轮换可能暴露的凭据。

## 8. 季度隔离恢复演练

第一季度月（1/4/7/10 月）的第一个星期一由 timer 发送提醒，不自动恢复，也无权访问 age identity。人工演练必须使用 `RESTORE_ENV=isolated`、`/srv/portfolio-restore/{drill}`、项目名 `portfolio-restore-*`、独立网络/卷和回环 `28080`；禁止挂载 `/opt/portfolio`、`/etc/portfolio`、`/var/lib/portfolio` 或生产 Docker 卷。

选择最近已验证 set，并至少选择一个存在、非当前的历史 `content_revision`。age identity 只能通过 root-only 临时挂载或受保护文件描述符交互提供，不得出现在参数、环境、历史或日志。COS 目标必须是独立非生产演练桶/前缀，使用短期身份。

### 精确执行入口

先按发布内 `deploy/restore/README.md` 的清单准备精确命名的 env、rclone、TLS 和管理员认证材料；所有文件必须 owner-only。下面流程会在 `/run` 的 tmpfs 上 create-only 创建本次目录和 sentinel，然后暂停，供受控秘密注入流程把材料写到该目录。受控记录中的非秘密位置/principal 元数据应预先载入当前 root shell，秘密值只允许存在于这些 tmpfs 文件中。使用 FD 模式时不要在 tmpfs 中创建 `age-identity`。

下面是唯一受支持的季度演练入口。占位值必须从受控记录填写；不要把填写后的命令、环境或输出提交到 Git，也不要启用 shell trace：

```bash
sudo -i
set -euo pipefail
set +x
umask 077

DRILL_ID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
SET_ID='<YYYYMMDDTHHMMSSZ-12hex>'
RELEASE_ID='<12hex-12hex>'
HISTORICAL_REVISION_ID='<canonical-uuid>'
REVIEWED_NGINX_IMAGE_ID='sha256:<64hex>'
DRILL_ROOT="/srv/portfolio-restore/$DRILL_ID"
SECRETS_PARENT='/run/portfolio-restore-secrets'
SECRETS="/run/portfolio-restore-secrets/$DRILL_ID"

cleanup_unstarted_secrets() {
  local status="${1:-1}" secret
  trap - EXIT HUP INT TERM
  if ((status != 0)) &&
     [[ -d "$SECRETS" && ! -L "$SECRETS" ]] &&
     [[ "$(stat -Lc '%u:%a' "$SECRETS" 2>/dev/null || true)" == '0:700' ]] &&
     [[ -f "$SECRETS/.portfolio-restore-secrets" &&
        ! -L "$SECRETS/.portfolio-restore-secrets" ]] &&
     [[ "$(<"$SECRETS/.portfolio-restore-secrets")" == "$DRILL_ID" ]]; then
    while IFS= read -r -d '' secret; do
      if [[ -f "$secret" && ! -L "$secret" ]]; then
        chmod 0600 -- "$secret" 2>/dev/null || true
        : >"$secret" 2>/dev/null || true
      fi
      rm -f -- "$secret" 2>/dev/null || true
    done < <(find "$SECRETS" -xdev -mindepth 1 -maxdepth 1 \
      \( -type f -o -type l \) -print0 2>/dev/null)
    rmdir -- "$SECRETS" 2>/dev/null || true
  fi
  exit "$status"
}
trap 'cleanup_unstarted_secrets $?' EXIT
trap 'exit 130' HUP INT TERM

if [[ ! -e "$SECRETS_PARENT" && ! -L "$SECRETS_PARENT" ]]; then
  install -d -o root -g root -m 0700 -- "$SECRETS_PARENT"
fi
[[ -d "$SECRETS_PARENT" && ! -L "$SECRETS_PARENT" ]]
[[ "$(stat -Lc '%u:%a' "$SECRETS_PARENT")" == '0:700' ]]
[[ "$(findmnt -T "$SECRETS_PARENT" -n -o FSTYPE)" == tmpfs ]]
mkdir -m 0700 -- "$SECRETS"
printf '%s\n' "$DRILL_ID" >"$SECRETS/.portfolio-restore-secrets"
chmod 0600 -- "$SECRETS/.portfolio-restore-secrets"

printf '%s\n' \
  "Inject the exact owner-only files listed in deploy/restore/README.md into:" \
  "$SECRETS" >&2
read -r -p 'Type PREPARED only after controlled secret injection: ' confirmation
[[ "$confirmation" == PREPARED ]]
unset confirmation

# 这些值须预先从受控记录载入当前 root shell。
: "${PRODUCTION_COS_ACCOUNT_ID:?required}"
: "${PRODUCTION_COS_BUCKET:?required}"
: "${PRODUCTION_COS_REGION:?required}"
: "${PRODUCTION_COS_PRINCIPAL_ID:?required}"
: "${BACKUP_DESTINATION_ACCOUNT_ID:?required}"
: "${BACKUP_DESTINATION_BUCKET:?required}"
: "${BACKUP_DESTINATION_REGION:?required}"
: "${BACKUP_VERIFY_PRINCIPAL_ID:?required}"
: "${BACKUP_UPLOAD_PRINCIPAL_ID:?required}"
: "${RESTORE_DRILL_COS_ACCOUNT_ID:?required}"
: "${RESTORE_DRILL_COS_BUCKET:?required}"
: "${RESTORE_DRILL_COS_REGION:?required}"
: "${RESTORE_DRILL_COS_PRINCIPAL_ID:?required}"
: "${BACKUP_REMOTE:?required}"                # remote:exact-backup-bucket
: "${RESTORE_RELEASE_REMOTE:?required}"       # remote:non-root-release-prefix
: "${RESTORE_DRILL_COS_REMOTE:?required}"     # remote:exact-drill-bucket
: "${BACKUP_PREFIX:?required}"
: "${RESTORE_DRILL_CREDENTIAL_EXPIRES_AT:?required}" # 真实 STS RFC3339 到期时间
: "${RESTORE_TLS_SERVER_NAME:?required}"

[[ ! -e "$DRILL_ROOT" && ! -L "$DRILL_ROOT" ]]
[[ -d "$SECRETS" && ! -L "$SECRETS" ]]
[[ "$(findmnt -T "$SECRETS" -n -o FSTYPE)" == tmpfs ]]
[[ "$(<"$SECRETS/.portfolio-restore-secrets")" == "$DRILL_ID" ]]

export RESTORE_ENV=isolated
export RESTORE_API_PORT=28080
export RESTORE_NGINX_PORT=28443

export RESTORE_APP_ENV="$SECRETS/app.env"
export RESTORE_POSTGRES_ENV="$SECRETS/postgres.env"
export BACKUP_VERIFY_RCLONE_CONFIG="$SECRETS/backup-verifier.rclone.conf"
export BACKUP_UPLOAD_RCLONE_CONFIG="$SECRETS/report-uploader.rclone.conf"
export RESTORE_DRILL_COS_CONFIG="$SECRETS/drill-cos.rclone.conf"
export RESTORE_TLS_CERTIFICATE="$SECRETS/tls.crt"
export RESTORE_TLS_PRIVATE_KEY="$SECRETS/tls.key"
export RESTORE_TLS_CA_CERT="$SECRETS/ca.crt"
export RESTORE_ADMIN_AUTH_FILE="$SECRETS/admin-auth.json"

export PRODUCTION_COS_ACCOUNT_ID PRODUCTION_COS_BUCKET PRODUCTION_COS_REGION
export PRODUCTION_COS_PRINCIPAL_ID
export BACKUP_DESTINATION_ACCOUNT_ID BACKUP_DESTINATION_BUCKET
export BACKUP_DESTINATION_REGION BACKUP_VERIFY_PRINCIPAL_ID
export BACKUP_UPLOAD_PRINCIPAL_ID
export RESTORE_DRILL_COS_ACCOUNT_ID RESTORE_DRILL_COS_BUCKET
export RESTORE_DRILL_COS_REGION RESTORE_DRILL_COS_PRINCIPAL_ID
export BACKUP_REMOTE RESTORE_RELEASE_REMOTE RESTORE_DRILL_COS_REMOTE
export BACKUP_PREFIX RESTORE_DRILL_CREDENTIAL_EXPIRES_AT
export RESTORE_TLS_SERVER_NAME

export PRODUCTION_COS_LOCATIONS="${PRODUCTION_COS_ACCOUNT_ID}@${PRODUCTION_COS_BUCKET}@${PRODUCTION_COS_REGION}"
export BACKUP_DESTINATION_LOCATION="${BACKUP_DESTINATION_ACCOUNT_ID}@${BACKUP_DESTINATION_BUCKET}@${BACKUP_DESTINATION_REGION}"
export RESTORE_API_IMAGE="portfolio-api-archive:${RELEASE_ID}"
export RESTORE_POSTGRES_IMAGE="portfolio-postgres-17-archive:${RELEASE_ID}"
export RESTORE_NGINX_IMAGE="$REVIEWED_NGINX_IMAGE_ID"

# 脚本会自行清理 COMPOSE/proxy；任何调用者 RCLONE_* 都必须不存在。
while IFS= read -r name; do unset "$name"; done < <(compgen -A variable RCLONE_)
unset RESTORE_AGE_IDENTITY AGE_IDENTITY AGE_IDENTITIES AGE_SECRET_KEY AGE_KEY

read -r -p 'Offline age identity absolute path: ' OFFLINE_AGE_IDENTITY
[[ -f "$OFFLINE_AGE_IDENTITY" && ! -L "$OFFLINE_AGE_IDENTITY" ]]
[[ "$(stat -Lc '%u' "$OFFLINE_AGE_IDENTITY")" == 0 ]]
identity_mode="$(stat -Lc '%a' "$OFFLINE_AGE_IDENTITY")"
(( (8#$identity_mode & 8#077) == 0 ))

# FD 9 只属于命令子进程；identity 路径和值不会进入 restore-drill argv。
# 脚本会在网络、Docker 或 adapter 调用前复制到 tmpfs、关闭 FD，
# 并在第三次解密尝试后销毁 tmpfs identity。
bash -c 'exec "$@" --age-identity-fd 9' _ \
  /opt/portfolio/current-ops/deploy/restore/restore-drill.sh \
  --root "$DRILL_ROOT" \
  --drill-id "$DRILL_ID" \
  --set-id "$SET_ID" \
  --historical-revision "$HISTORICAL_REVISION_ID" \
  9<"$OFFLINE_AGE_IDENTITY"

[[ ! -e "$SECRETS" && ! -L "$SECRETS" ]]
trap - EXIT HUP INT TERM
unset OFFLINE_AGE_IDENTITY
```

需要验证多个历史版本时，重复追加 `--historical-revision "$ANOTHER_UUID"`。`RESTORE_DRILL_CREDENTIAL_EXPIRES_AT` 必须是本次真实 STS 凭据的到期时间，调用时剩余 15–60 分钟，不能伪造为“当前时间 + 1 小时”。`app.env` 与 `postgres.env` 必须分别恰好包含 `restore-drill.sh` 的 `validate_isolated_env_files` 所列键；任何多余 SMTP、生产凭据或未知键都会被拒绝。

### 恢复顺序

1. 记录 monotonic start 和 set 创建时间，下载 set manifest、VERIFIED、加密 artifact、blob 引用和匹配 release 元数据。
2. 在解密前由 verifier 校验所有密文 checksum 和引用；发布包缺失时先完成精确 Git 标签重建并逐字段匹配 release identity。
3. 解密 database dump 和权威媒体 manifest；先验证 dump list。
4. 从归档中加载固定 PostgreSQL 17 镜像，在空、独立数据库 `pg_restore --clean --if-exists --no-owner`；创建非登录角色等价物，验证 runtime 可 DML 但无 DDL。
5. 解析闭包：所有当前 publication revision，加至少一个指定历史非当前 revision；扩展其 original/READY variants，并与备份 manifest 做 asset/variant/provider/size/MIME/SHA 精确匹配。
6. 完整验证解密 Local tar 后，只把所选 allowlist 成员逐个安全写入空的隔离卷；不使用 unchecked `extractall`，忽略归档 owner/mode。
7. 对 COS 行从备份 blob 读回、验证，再写到 `drills/{drillId}/blobs/{sha}`；仅在演练数据库把 bucket/region/key 参数化映射为演练位置。Local 行始终只映射隔离卷。
8. 证明每行恰好映射一次，且没有生产/备份账号、桶、前缀和宿主路径残留；再次核对字节。
9. 以匹配 API 和管理/公共资源启动隔离应用，关闭 SMTP、jobs 和生产外联；通过生产等价本地 Nginx 访问。
10. 当前媒体走 `/api/public/media/...`，历史-only 媒体走认证的管理预览；逐个比较最终响应体 SHA-256 和规范 Content-Type。直接数据库或对象存储检查不能替代 HTTP 字节检查。
11. 读取当前项目和历史 revision，执行“从历史恢复为新草稿”但不发布，确认混合 provider 引用仍完整。
12. 计算 backup age 和 monotonic elapsed；要求 RPO ≤24h、RTO ≤4h。
13. 生成 canonical 脱敏报告和 detached SHA，先上传 `drill-reports/{drillId}`，再由 verifier 读回。
14. 只有远端报告验证成功后，才用静态参数化 SQL 幂等写生产 `RESTORE_DRILL` maintenance 行。生产暂不可用时保留远端报告，稍后用相同 UUID/checksum 重试。
15. 停止隔离项目，撤销演练 COS 身份，确认生产资源未引用后清理受界定 root/卷/演练前缀。

恶意 Local tar 测试必须在 extraction 前拒绝绝对/父级/反斜杠路径、链接、FIFO/socket/device、sparse、PAX、重复规范/大小写路径、类型冲突、allowlist 之外或缺少的成员。

## 9. 演练报告内容

只允许：drill UUID、set/release ID、成功/失败、RFC3339 起止时间、RPO/RTO 秒数、非敏感计数、checksum 摘要、allowlisted 错误类别、清理/凭据撤销布尔证据。禁止 path、账号/桶/object key、host、credential、异常原文、SQL、PII 和 TOTP 材料。

报告先远端后生产记录；无匹配生产 maintenance 行的演练尚未完成。失败也尝试按同一顺序保存脱敏 FAILED 报告。

## 10. 真实事故恢复

1. 隔离受影响主机/身份，冻结 DNS 或写流量，保留日志和卷证据。
2. 选择事故前最近 VERIFIED set；先在隔离环境重复上述验证，确认闭包和 release 可用。
3. 新建生产替代资源，不原地覆盖唯一副本。按 PostgreSQL、Local、COS 映射、应用、Nginx、DNS 顺序恢复。
4. 通过真实接口验证内容、媒体字节、管理员登录、审计、留言写入和发布草稿；SMTP/jobs 初始保持关闭。
5. 轮换所有可能暴露的数据库、COS、SMTP、rclone、会话/TOTP 材料，撤销旧身份；管理员执行安全恢复并撤销全部会话。
6. 恢复流量后加强监控，补齐事件时间线、实际 RPO/RTO、根因和改进项；证据继续脱敏。

绝不因为“备份看起来存在”直接恢复到生产。解密密钥丢失无法技术绕过；发现缺失闭包时应选择更早的自包含 VERIFIED set，而不是伪造 manifest。
