# 发布与恢复证据模板

复制本文件到受访问控制的运维记录系统。只填写脱敏值；不要把填写后的生产记录提交到 Git。

## 记录元数据

| 字段 | 值 |
|---|---|
| 变更/演练 UUID | `<uuid>` |
| 类型 | `RELEASE / ROLLBACK / DNS_CUTOVER / RESTORE_DRILL / INCIDENT` |
| 执行者 / 复核者 | `<operator-id> / <reviewer-id>` |
| 窗口（RFC3339） | `<start> — <finish>` |
| 结果 | `SUCCEEDED / FAILED / ROLLED_BACK` |
| 允许列表错误类别 | `<none-or-category>` |

禁止填写：用户名对应的私人联系方式、访客邮箱、密钥、token、cookie、TOTP URI/seed、恢复码、数据库 DSN、私网/SSH 地址、桶名、object key、完整路径、异常原文或命令环境。

## 基础设施门

| 检查 | 证据（摘要/布尔值/受控记录 ID） | 结果 |
|---|---|---|
| Ubuntu 22.04 补丁级别 | `<version>` | `PASS/FAIL` |
| APT 签名源 host/suite/component/architecture | `<redacted-source-summary>/<evidence-id>` | `PASS/FAIL` |
| Bootstrap 宿主包精确版本/架构 | `<dpkg-query-artifact-id>` | `PASS/FAIL` |
| Bootstrap 系统二进制 root-owned / 非 group-world writable | `<command-gate-artifact-id>` | `PASS/FAIL` |
| Docker Server 26+ / Compose v2 | `<versions>` | `PASS/FAIL` |
| NTP 同步 | `<checked-at>` | `PASS/FAIL` |
| 腾讯地域与辖区已确认 | `<region-code>/<jurisdiction>/<evidence-id>` | `PASS/FAIL` |
| 端口边界（80/443 Nginx；18080 loopback；无 5432） | `<checked-at>` | `PASS/FAIL` |
| env 所有权/模式 | `<checked-at>` | `PASS/FAIL` |
| TLS 主机名、链、密钥匹配、有效期 | `<cert-fingerprint-and-expiry>` | `PASS/FAIL` |
| 磁盘/inode/命名卷余量 | `<redacted-metrics>` | `PASS/FAIL` |
| Local 卷名/挂载/不透明标记一致 | `<boolean-evidence>` | `PASS/FAIL` |
| COS lifecycle 独立只读验证 | `<rule-hash>/<checked-at>` | `PASS/FAIL/N/A` |
| 当前发布 cleanup 当前边界成功 | `<release-id>/<boundary-date>` | `PASS/FAIL/N/A` |

## ICP 与 DNS 门

| 字段 | 值 |
|---|---|
| ICP 状态 | `PENDING / APPROVED` |
| 备案号 | `<approved-number-or-blank>` |
| 批准证据记录 ID | `<evidence-id-or-blank>` |
| 公网域名门 | `DISABLED / ENABLED` |
| 变更前 DNS 摘要 / TTL | `<digest>/<seconds>` |
| 变更后权威 DNS 摘要 | `<digest>` |
| DNS 回退目标已保存 | `YES/NO` |

大陆环境中，`PENDING`、空备案号或公网门关闭时，公网切换结果必须是 `NOT_RUN`；本地部署可继续。

## 发布身份

| 字段 | 值 |
|---|---|
| Release ID | `<12hex-12hex>` |
| Git commit | `<40-lowercase-hex>` |
| Protected source tag | `refs/tags/portfolio-release/<release-id>` |
| 仓库 visibility / tag 禁止更新与删除规则 | `PRIVATE/<rule-evidence-id>` |
| 远端标签解析一致 | `PASS/FAIL` |
| Bundle SHA-256 / bytes | `<sha256>/<integer>` |
| Bundle envelope SHA-256 / bytes | `<sha256>/<integer>` |
| release.json SHA-256 | `<sha256>` |
| Bootstrap kit SHA-256 / bytes | `<sha256>/<integer>` |
| Bootstrap manifest SHA-256 / bytes | `<sha256>/<integer>` |
| Standalone installer SHA-256 / bytes | `<sha256>/<integer>` |
| Kit envelope SHA-256 / bytes | `<sha256>/<integer>` |
| 独立认证记录 ID（不来自 envelope/manifest） | `<evidence-id>` |
| API image ID | `sha256:<digest>` |
| PostgreSQL image ID | `sha256:<digest>` |
| Build input lock digest | `<sha256>` |
| Exact rebuild comparison（如执行） | `IDENTICAL/NOT_RUN/FAIL` |

## 质量门

| 命令/套件 | 结果 | 脱敏日志摘要 |
|---|---|---|
| Public frontend build/test | `PASS/FAIL` | `<artifact-id>` |
| Admin unit/E2E build/test | `PASS/FAIL` | `<artifact-id>` |
| Maven verify | `PASS/FAIL` | `<tests>/<artifact-id>` |
| Release artifact contract | `PASS/FAIL` | `<artifact-id>` |
| Compose contract | `PASS/FAIL` | `<artifact-id>` |
| Nginx contract | `PASS/FAIL` | `<artifact-id>` |
| COS lifecycle contract | `PASS/FAIL` | `<artifact-id>` |
| Deploy state machine | `PASS/FAIL` | `<artifact-id>` |
| Backup contract | `PASS/FAIL` | `<artifact-id>` |
| Restore safety contract | `PASS/FAIL` | `<artifact-id>` |
| Forbidden-value scan | `PASS/FAIL` | `<artifact-id>` |
| Docker history / rendered config manual review | `PASS/FAIL` | `<reviewer-id>` |

## 发布前恢复点

| 字段 | 值 |
|---|---|
| Backup set ID | `<set-id>` |
| Set manifest SHA-256 | `<sha256>` |
| Database artifact verified | `YES/NO` |
| Local tar（含空 tar）verified | `YES/NO` |
| COS closure/sample verified | `YES/NO/N/A` |
| 独立 verifier read-back | `YES/NO` |
| VERIFIED marker matched | `YES/NO` |
| Backup age at deployment | `<seconds>` |
| 四类备份 principal / backup.env / prune guard | `<evidence-id> / PASS/FAIL` |
| 四个 backup unit 有效 FragmentPath / 无 drop-in / cache fresh | `<evidence-id> / PASS/FAIL` |
| 两个 timer enabled + active/waiting | `<evidence-id> / PASS/FAIL` |
| 首次空库发布后的立即 verified backup | `<set-id>/<verified-marker-digest> / PASS/FAIL/N/A` |

上述任一为 `NO` 或 age > 86400 时，发布状态必须是 `BLOCKED_BEFORE_SWITCH`。

## 切换状态机

| 阶段 | 开始/完成时间 | 结果/摘要 |
|---|---|---|
| Bundle pre-extraction validation | `<times>` | `PASS/FAIL` |
| Manifest/image identity validation | `<times>` | `PASS/FAIL` |
| Create-only public assets | `<times>` | `PASS/FAIL` |
| Protected release.env switch | `<times>` | `PASS/FAIL` |
| PostgreSQL health | `<times>` | `PASS/FAIL` |
| API migration/readiness | `<times>` | `PASS/FAIL` |
| `api-local` smoke | `<times>` | `PASS/FAIL` |
| Cleanup/provider evidence gate | `<times>` | `PASS/FAIL` |
| Admin symlink switch | `<times>` | `PASS/FAIL` |
| BaoTa Nginx test/reload | `<times>` | `PASS/FAIL` |
| `nginx-local` smoke | `<times>` | `PASS/FAIL` |
| Public cutover preflight | `<times>` | `PASS/FAIL/NOT_RUN` |
| `public` smoke | `<times>` | `PASS/FAIL/NOT_RUN` |
| current / previous markers | `<ids>` | `PASS/FAIL` |
| current-release / release.env / current-admin / current-ops 四指针一致 | `<release-id>/<evidence-id>` | `PASS/FAIL` |
| Three-release/assets prune | `<times>` | `PASS/FAIL` |

## 冒烟证据

记录状态、Content-Type、缓存策略和内容摘要，不记录响应正文或 cookie。

| 路径族 | api-local | nginx-local | public |
|---|---|---|---|
| readiness | `<result>` | `N/A` | `N/A` |
| `/`, `/zh-CN`, `/en`, privacy | `<result>` | `<result>` | `<result/not-run>` |
| public site/projects + ETag | `<result>` | `<result>` | `<result/not-run>` |
| JSON API 404 / anonymous admin 401 | `<result>` | `<result>` | `<result/not-run>` |
| sitemap / robots | `<result>` | `<result>` | `<result/not-run>` |
| admin HTML no-store | `N/A` | `<result>` | `<result/not-run>` |
| hash asset SHA + immutable cache | `N/A` | `<sha/result>` | `<sha/result/not-run>` |
| representative media SHA/Content-Type | `N/A` | `<sha/type/result>` | `<sha/type/result/not-run>` |

## 回滚（如发生）

| 字段 | 值 |
|---|---|
| 触发类别 | `<allowlisted-category>` |
| 切换前 current / previous | `<release-ids>` |
| 回滚目标 | `<release-id>` |
| 数据库 down migration 执行 | 必须为 `NO` |
| 旧 env/API/admin/Nginx 恢复 | `PASS/FAIL` |
| 回滚后 api-local/nginx-local | `PASS/FAIL` |
| 最终 marker/env 一致 | `PASS/FAIL` |

## 恢复演练（如适用）

| 字段 | 值 |
|---|---|
| Drill UUID / selected set / release | `<ids>` |
| 历史非当前 revision 已验证 | `YES/NO` |
| 隔离 root/project/loopback port | `<bounded-id>/<project>/<port>` |
| 生产卷/目录/凭据引用为零 | `PASS/FAIL` |
| 恶意 tar pre-extraction contract | `PASS/FAIL` |
| DB restore + runtime no-DDL | `PASS/FAIL` |
| Local/COS mixed mapping exact | `PASS/FAIL/N/A` |
| 当前/历史 HTTP 媒体字节验证 | `<counts/result>` |
| 历史恢复为未发布新草稿 | `PASS/FAIL` |
| RPO / RTO（秒） | `<rpo>/<rto>` |
| Redacted report SHA-256 | `<sha256>` |
| 远端上传后 verifier read-back | `PASS/FAIL` |
| 匹配生产 RESTORE_DRILL 行 | `PASS/FAIL/PENDING` |
| 演练身份撤销/隔离资源清理 | `PASS/FAIL` |

## 最终结论

- 当前 current-release/release.env/current-admin/current-ops：`<consistent-or-detail>`
- 公网状态：`UNCHANGED / CUT_OVER / ROLLED_BACK / DISABLED`
- 仍需跟进的安全项：`<allowlisted-summary-or-none>`
- 执行者确认：`<operator-id>/<time>`
- 独立复核：`<reviewer-id>/<time>`

只有全部必需门为 PASS、失败状态已安全恢复、且记录通过复核，才可标记完成。
