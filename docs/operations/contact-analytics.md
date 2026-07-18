# 联系表单、邮件与隐私统计运维手册

本文档对应 `contact_message`、独立的 `email_outbox`、`analytics_event`、
`analytics_daily`、`analytics_retention_checkpoint`、`background_job` 和
`maintenance_run`。命令示例只读取脱敏状态；除“受控聚合重放”外，不应直接修改表。

## 安全边界

- 联系表单提交会在一个事务中同时写入消息和邮件 outbox，HTTP `202` 不等待 SMTP。
- SMTP 是至少一次（at-least-once）投递。进程可能在供应商已接收、数据库尚未标记
  `SENT` 的窗口崩溃，因此允许重复邮件，但不允许丢失已提交的通知。
- 统计默认关闭，只有浏览器明确同意后才可创建标识并发送事件。`DNT: 1` 或
  `Sec-GPC: 1` 会在服务端再次短路，返回 `204` 且不写入事件。
- 数据库不保存 IP、IP 哈希、原始浏览器/会话 ID、完整 User-Agent、完整来源 URL
  或查询参数。User-Agent 只在请求内存中用于设备和爬虫分类。
- 不要在日志、工单、聊天或排障 SQL 输出中复制访客姓名、邮箱、主题、正文、
  浏览器 ID、日 HMAC、SMTP 凭据或 HMAC secret。
- Nginx 对 `/api/public/events` 应关闭 access log，或使用专用格式，明确排除客户端
  IP、User-Agent、Referer、Cookie 和 query string。

## 生产配置

`deploy/.env.example` 只列出名称与安全默认，不包含可用凭据。空值必须通过受保护的
部署环境或 secret manager 注入，不要把生产值写回仓库。

| 环境变量 | 默认值 | 运行规则 |
| --- | --- | --- |
| `PORTFOLIO_EMAIL_ENABLED` | `false` | `false` 只停用 SMTP sender、轮询 worker 和 mail health；联系表单仍写消息及 `PENDING` outbox。 |
| `PORTFOLIO_EMAIL_FROM` | 空 | 启用邮件时必填，必须是单一有效邮箱地址。 |
| `PORTFOLIO_OWNER_EMAIL` | 空 | 联系服务启动所需的站点所有者邮箱，也是通知目标；即使暂时关闭 SMTP 也要配置。 |
| `PORTFOLIO_MAIL_ID_DOMAIN` | `yychainsaw.xyz` | 稳定 RFC `Message-ID` 的域名；必须是规范 DNS 域名。 |
| `SMTP_HOST` | 空 | 启用邮件时必填；只允许经过校验的 SMTP 主机名。 |
| `SMTP_PORT` | `587` | STARTTLS SMTP 端口，范围 1–65535。 |
| `SMTP_USERNAME` | 空 | 启用邮件时必填；只能由运行时 secret 注入。 |
| `SMTP_PASSWORD` | 空 | 启用邮件时必填；只能由运行时 secret 注入。 |
| `PORTFOLIO_CONTACT_DEDUPE_SECRET` | 空 | 标准、规范 Base64，解码后至少 32 个随机字节；所有副本必须一致。 |
| `PORTFOLIO_ANALYTICS_HMAC_SECRET` | 空 | 标准、规范 Base64，解码后至少 32 个随机字节；`prod` 启动时强制要求，所有副本必须一致。 |
| `PORTFOLIO_JOBS_WORKER_ENABLED` | `prod` 默认 `false`，示例为 `true` | 启用数据库后台任务 worker；生产分析校验要求为 `true`。 |
| `PORTFOLIO_ANALYTICS_MAINTENANCE_SCHEDULING_ENABLED` | `true` | 控制分析聚合/保留调度；生产校验要求为 `true`。 |

当前固定应用属性如下：

- 邮件轮询间隔 `10s`、租约 `2m`、单次领取 `10` 封；SMTP 连接、读取和写入超时
  各 `10s`，STARTTLS、认证和服务端证书身份校验均为强制。
- JNDI mail session、隐式 SSL、trust-all、定制 socket factory、协议/密码套件覆盖和
  mail debug 均被拒绝。需要 SMTPS 时必须另建并测试独立 profile，不能降级明文连接。
- 后台任务默认初始等待 `5s`、轮询 `1s`、租约 `30m`。任务失败按
  `min(2^attempts, 3600)` 秒重试，第 10 次失败进入 `DEAD`。

### Secret 轮换

先把新 secret 写入 secret manager，再在一个受控发布中让所有实例同时切换；不能让
不同实例并行使用不同值，也不能把 secret 打到终端、日志或进程参数中。

- 联系去重 secret 轮换后，旧、新指纹不能比较，正在进行的十分钟去重窗口会被重置；
  历史指纹仍不可逆。
- 分析 HMAC secret 轮换后，同一个浏览器在同一站点日会得到新的 visitor/session 日键，
  可能使该日 UV 增加并切断十秒去重连续性。应在 `Asia/Hong_Kong` 新自然日尚未接收
  事件前切换，并记录变更时间；轮换不会把历史日键重新关联为“人”。
- 备份/恢复必须从独立 secret 管理系统恢复相同值，数据库备份本身不应包含这些值。

## 联系提交与 PII 生命周期

### 输入与去重

`POST /api/public/contact` 只接受严格 JSON，请求体上限 32,768 原始字节；未知字段、
重复字段、错误类型和尾随 token 都会被拒绝。字段边界为：姓名 100、邮箱 320、主题
160、正文 5,000、honeypot `website` 200 个字符，并要求 `privacyAccepted=true`。
文本会进行 NFC、Unicode 空白和换行规范化，单行控制字符被拒绝。

真实提交按规范化后的邮箱、主题和正文计算带 secret 的指纹，并在滚动十分钟窗口内
去重；恰好十分钟仍视为重复，超过十分钟才重新接受。并发提交由 PostgreSQL 事务级
advisory lock 串行化。honeypot 命中和重复请求都返回相同的通用 `202`，响应不包含
消息 ID 或访客值。公共限流为同一已哈希来源每 15 分钟 5 次。

### 保留和删除

- `CONTACT_RETENTION` 在应用就绪时及每天 `03:00 Asia/Hong_Kong` 入队。
- cutoff 是执行时刻按香港时区减去一个日历年，不是简单写死 365 天。
- 每批删除最多 500 条，任务继续处理直到不足一批；删除 `contact_message` 会级联删除
  对应 outbox。有效的 `SENDING` 租约不会被保留任务抢删。
- 管理员可随时调用 `DELETE /api/admin/messages/{messageId}` 提前删除 PII。若邮件仍在
  未过期的 `SENDING` 租约中会返回冲突，应等待租约恢复后再删。
- `ARCHIVED` 只是收件箱状态，不会删除 PII。手工删除及状态变更都要求管理员登录、
  mutation CSRF，并写入仅含消息 UUID、状态与创建日期的脱敏审计记录；删除后该审计
  记录保留，但访客 PII 不保留。

## SMTP outbox

### 稳定身份、租约与重试

每条消息创建唯一且持久的
`<portfolio-contact-{message UUID}@{PORTFOLIO_MAIL_ID_DOMAIN}>`，所有自动和手工重试
复用同一 `Message-ID`。供应商可以据此识别崩溃窗口造成的重复投递。

worker 每 10 秒先恢复过期的 `SENDING`，再通过 `FOR UPDATE SKIP LOCKED` 领取最多
10 条。领取会增加 `attempts` 并建立两分钟租约；发送前再次续租。过期租约转成
`FAILED`，第 10 次及以后转成 `DEAD`，安全错误分类为
`DELIVERY_INTERRUPTED`。任何晚到的旧 worker 都不能越过 lease owner 与 attempt fence
覆盖新状态。

一次自动发送失败后的计划为：

| 已完成尝试次数 | 下一次自动尝试 |
| ---: | ---: |
| 1 | 1 分钟 |
| 2 | 5 分钟 |
| 3 | 15 分钟 |
| 4 | 60 分钟 |
| 5 | 240 分钟 |
| 6 | 720 分钟 |
| 7–9 | 24 小时 |
| 10 | `DEAD`，停止自动重试 |

只持久化异常类名和下列脱敏分类，不保存 SMTP 响应正文：
`SMTP_AUTHENTICATION_FAILED`、`SMTP_CONNECTION_FAILED`、
`MESSAGE_PREPARATION_FAILED`、`SMTP_DELIVERY_FAILED`、
`UNEXPECTED_DELIVERY_FAILURE`、`DELIVERY_INTERRUPTED`。

### 手工重试与 DEAD 排障

管理员后台的重试动作调用
`POST /api/admin/messages/{messageId}/email/retry`。它只接受 `FAILED`/`DEAD`，把状态
改为 `PENDING`、清除脱敏错误并立即到期，但保留 `attempts` 和稳定 `Message-ID`；动作
要求管理员 session、CSRF，并写脱敏审计。不要用 SQL 把 outbox 直接改回 `PENDING`。

先用不读取 PII 的查询判断原因（在已受保护、不会把密码放进参数的 `psql` 会话执行）：

```sql
SELECT id,
       contact_message_id,
       status,
       attempts,
       next_attempt_at,
       lease_until,
       nullif(split_part(last_error_summary, '|', 2), '') AS error_category,
       updated_at
FROM portfolio.email_outbox
WHERE status IN ('FAILED', 'DEAD', 'SENDING')
ORDER BY updated_at, id;
```

处理顺序：

1. `SMTP_AUTHENTICATION_FAILED`：修复运行时用户名/密码或供应商授权，滚动重启并确认
   mail health；不得把凭据贴入日志或工单。
2. `SMTP_CONNECTION_FAILED`：核对 DNS、出口防火墙、587/STARTTLS 和证书链。
3. `MESSAGE_PREPARATION_FAILED`：检查 from/owner/domain 配置和模板版本，不能绕过头字段
   校验。
4. `SMTP_DELIVERY_FAILED`：在供应商侧用稳定 `Message-ID` 查投递结果，再决定是否手工
   重试；这一步可避免无意义重复。
5. `DELIVERY_INTERRUPTED` 或过期 `SENDING`：保持 worker 开启；下一次轮询会自动回收。
6. 原因消除后只通过管理员重试动作重放一条，观察变为 `SENT` 后再批量处理。

如果 `PORTFOLIO_EMAIL_ENABLED=false`，关闭期间新建的通知会安全停留在 `PENDING`；
既有 `FAILED`、`DEAD`、`SENDING` 保持原状态。重新启用后，worker 按
`next_attempt_at, created_at, id` 领取已经到期的 `PENDING`/`FAILED`，并恢复过期的
`SENDING`；`DEAD` 仍须管理员手工重试。关闭邮件并不删除 outbox。

## 隐私统计合同

### 同意、标识与过滤

浏览器端上线前必须满足以下合同：

- 默认不同意；拒绝或撤回后停止未来采集并清除浏览器标识。撤回无法反查或删除已经
  去身份化的日聚合。
- `DNT=1`（以及支持时的 GPC）不显示同意提示、不创建标识、不获取统计 CSRF token、
  不发送事件。服务端对 `DNT: 1` 和 `Sec-GPC: 1` 另有防御性短路。
- visitor ID 是 128 位随机 base64url，保存在 `localStorage`，达到 30 天即轮换；
  session ID 独立生成并保存在 `sessionStorage`，达到 30 分钟无活动即轮换。仅真正
  发出事件才更新会话活动时间。
- 服务端只在方法局部读取原始 ID，并按
  `{siteDate}\nvisitor\n{id}` / `{siteDate}\nsession\n{id}` 生成 64 字符小写十六进制日 HMAC；
  原始 ID 不落库、不入日志。

同意后的批次仍受 32 KiB/20 个事件限制和每分钟 60 次公共限流。`client_event_id`
阻止网络重试重复；同一 `(sessionDayKey, eventType, pageKey, projectId)` 在十秒滚动
窗口内去重，恰好十秒仍抑制，超过十秒才接受。站点日完全由服务端接收时间按
`Asia/Hong_Kong` 计算，客户端时间不能改写日期。

当前不可随意扩展的 `analytics-rules-v1` 为：

- 事件：`PAGE_VIEW`、`PROJECT_VIEW`、`RESUME_DOWNLOAD`、`DEMO_DOWNLOAD`、
  `OUTBOUND_CLICK`。任何携带 `projectId` 的事件都只接受当前已发布项目；
  `PROJECT_VIEW`/`DEMO_DOWNLOAD` 必须位于 `PROJECT_DETAIL` 并携带项目 ID，
  `PAGE_VIEW`/`OUTBOUND_CLICK` 在 `PROJECT_DETAIL` 也必须携带项目 ID、在其他页面则
  不得携带，`RESUME_DOWNLOAD` 不得携带项目 ID。
- 页面：`HOME`、`ABOUT`、`WORK`、`ROADMAP`、`CONTACT`、`PRIVACY`、
  `PROJECT_DETAIL`。
- crawler token：`bot`、`crawler`、`spider`、`preview`。命中后不落库。
- 来源只保留规范小写主机；站内/空来源为 `(direct)`，未列入规则的有效外部来源为
  `(none)`，allowlist 子域会收敛到其根域，绝不保存完整 URL。
- 设备仅为 `DESKTOP`、`MOBILE`、`TABLET`、`OTHER`，locale 仅为 `zh-CN`/`en`。

规则文件由代码按精确键、精确集合和精确版本校验。改变事件、页面、crawler 或来源
集合必须作为新的版本化规则发布并重新验证聚合/保留合同，不能直接热改 v1 文件。

### 指标定义

- **PV**：通过同意、规则、crawler 过滤和十秒去重的每个 `PAGE_VIEW` 计一次。
- **DAILY_UV**：每个香港自然日内，对匿名 visitor-day HMAC 去重后的页面浏览数；
  多日查询把每日 UV 相加，不是跨日唯一人数。
- **EVENT_COUNT**：通过相同过滤与去重的指定事件次数，可用于项目浏览、简历下载、
  Demo 下载、外链点击和页面浏览。

这些数据不是跨设备、跨浏览器或长期“人数”计数器。清理站点数据、visitor 30 天轮换、
HMAC secret 轮换以及使用多台设备都会产生新的匿名日键；同一日键也不能跨日关联。

## 聚合、延迟与 30 天保留

`analytics_daily` 按日期做确定性整日重建，并同时生成 `ALL`、`PAGE`、`PROJECT`、
`REFERRER`、`DEVICE`、`LOCALE` 维度。每个日期至少有七条 `ALL/(all)` 哨兵：PV、
DAILY_UV，以及五种事件的 EVENT_COUNT。

调度（全部为 `Asia/Hong_Kong`）：

- 应用就绪：入队前一日聚合、当前小时聚合、一次缺口修复和当日保留任务。
- 每天 `00:15`：重建前一日；每小时 `HH:15`：重建当日。
- 每小时 `HH:45`：在最近 28 天中入队最早的聚合缺口修复。
- 每天 `02:15`：执行原始事件保留；每天 `03:00`：执行联系消息保留。

正常情况下，当日看板最多约滞后一小时，完整前一日约在 `00:15` 后可用；队列积压、
数据库锁或重试会增加延迟。summary 的 `dataCompleteThrough` 只有在所选范围的每一天
都具备其单一聚合版本下的七条哨兵时才返回最新刷新时间，否则明确为 `null`。管理员
报告固定使用 `Asia/Hong_Kong`，响应 `Cache-Control: no-store`，且永不返回原始日键。

保留任务以执行时刻减去 30 个 24 小时为 cutoff：

- 先验证原始日期和聚合的完整、同版本、精确一致；单次最多自动修复 8 个缺口。
- 首次删除前写不可变 checkpoint。checkpoint 后该日拒绝迟到事件并冻结聚合，不能
  再重放或修改。
- 每批最多删除 5,000 条，一次 job 最多 10 批；仍有数据时在 5 秒后以新的幂等 key
  自动创建 successor。数据库函数同时强制 checkpoint、数据库时钟 30 天边界和批量
  上限，运行账号没有直接删除原始事件的权限。
- `analytics_event` 只保留 30 天；`analytics_daily` 和 checkpoint 不按此任务删除。

## 安全检查与故障定位

以下查询只返回状态、计数和时间。不要为了排障改成 `SELECT *`。

### 后台任务队列

```sql
SELECT id,
       job_type,
       idempotency_key,
       status,
       attempts,
       next_run_at,
       lease_until,
       last_error_summary,
       updated_at
FROM portfolio.background_job
WHERE job_type IN ('ANALYTICS_AGGREGATE', 'ANALYTICS_RETENTION', 'CONTACT_RETENTION')
ORDER BY updated_at DESC, id DESC
LIMIT 100;
```

`RUNNING` 超过 30 分钟租约后会被新 worker 重新领取；失败使用固定安全码并自动退避，
第 10 次进入 `DEAD`。不要手工重置旧 job 的 status/attempts/lease，也不要复用一个
已成功或失败 job 的幂等 key 来改变 payload。

### maintenance run

```sql
SELECT run_type,
       status,
       details,
       error_summary,
       started_at,
       finished_at
FROM portfolio.maintenance_run
WHERE run_type IN ('ANALYTICS_AGGREGATE', 'ANALYTICS_RETENTION', 'CONTACT_RETENTION')
ORDER BY started_at DESC, id DESC
LIMIT 100;
```

`ANALYTICS_AGGREGATE.details` 只有 `input_count`/`output_count`；
`ANALYTICS_RETENTION.details` 只有 `deleted_count`/`cutoff_epoch_second`；
`CONTACT_RETENTION.details` 只有 `deleted_count`。把 cutoff 转成人类时间可使用：

```sql
SELECT started_at,
       to_timestamp((details ->> 'cutoff_epoch_second')::bigint) AS cutoff,
       details ->> 'deleted_count' AS deleted_count,
       status,
       error_summary
FROM portfolio.maintenance_run
WHERE run_type = 'ANALYTICS_RETENTION'
ORDER BY started_at DESC
LIMIT 20;
```

### 覆盖、checkpoint 和原始数据年龄

```sql
SELECT site_date,
       count(*) FILTER (
           WHERE dimension = 'ALL' AND dimension_value = '(all)'
       ) AS all_sentinels,
       count(DISTINCT aggregation_version) AS versions,
       max(updated_at) AS refreshed_at
FROM portfolio.analytics_daily
GROUP BY site_date
ORDER BY site_date DESC
LIMIT 40;

SELECT site_date, aggregation_version, first_cutoff, created_at
FROM portfolio.analytics_retention_checkpoint
ORDER BY site_date DESC
LIMIT 40;

SELECT count(*) AS raw_count,
       min(received_at) AS oldest_received_at,
       max(received_at) AS newest_received_at
FROM portfolio.analytics_event;
```

一个完整日期的 `all_sentinels` 应为 7 且 `versions` 为 1。存在超过 30 天的原始记录时，
先检查分析 retention job、maintenance failure 和缺口覆盖，不要直接执行 `DELETE`；
数据库也会拒绝未 checkpoint 的删除。

## 受控聚合重放

当前没有 CSV/export 或面向管理员的 job mutation API。当前日应优先等待下一次
`HH:15` 自动重建；缺失日期优先等待 `HH:45` 修复。只有在确认自动恢复不能满足、
目标站点日的香港零点仍不早于执行时刻减 30 天、且不存在 retention checkpoint 时，
数据库操作员才可入队一个新的聚合 job。

在执行前记录工单并备份查询结果。通过不会把数据库密码暴露到命令行或 shell history
的受保护方式进入 `psql`，启用 `ON_ERROR_STOP`，为 `replay_id` 生成一个全新 UUID，
然后运行下列固定 SQL。不要改成直接删除/更新聚合表，也不要重置旧 job。

```sql
\set ON_ERROR_STOP on
\set target_date '2026-07-17'
\set replay_id '<fresh-uuid>'

BEGIN;
SELECT (
    to_char(:'target_date'::date, 'YYYY-MM-DD') = :'target_date'
    AND (:'target_date'::date::timestamp AT TIME ZONE 'Asia/Hong_Kong')
        >= clock_timestamp() - INTERVAL '30 days'
    AND :'target_date'::date
        <= (clock_timestamp() AT TIME ZONE 'Asia/Hong_Kong')::date
    AND NOT EXISTS (
        SELECT 1
        FROM portfolio.analytics_retention_checkpoint
        WHERE site_date = :'target_date'::date
    )
) AS replay_allowed
\gset

\if :replay_allowed
INSERT INTO portfolio.background_job (
    id,
    job_type,
    idempotency_key,
    payload,
    status,
    attempts,
    next_run_at,
    created_at,
    updated_at
) VALUES (
    :'replay_id'::uuid,
    'ANALYTICS_AGGREGATE',
    'analytics-manual-replay:' || :'target_date' || ':' || :'replay_id',
    jsonb_build_object(
        'siteDate', :'target_date',
        'aggregationVersion', 'analytics-rules-v1'
    ),
    'PENDING',
    0,
    clock_timestamp(),
    clock_timestamp(),
    clock_timestamp()
)
ON CONFLICT (idempotency_key) DO NOTHING
RETURNING id, job_type, status, idempotency_key;
COMMIT;
\else
ROLLBACK;
\echo 'Replay rejected: date is out of range or already checkpointed.'
\endif
```

上面的 UUID 是不可执行的文档占位符，每次操作必须替换为新生成的 UUID；不得在生产
重复使用。日期校验会在 worker 真正执行时按当时的 30 天边界再次进行；不要选择贴近
最早边界的日期，应为排队和重试预留时间（通常优先限制在最近 28 天）。worker 完成后
检查该 job 为 `SUCCEEDED`、对应 maintenance run 为
`SUCCEEDED`、七条哨兵和版本正确，再查看管理员 summary 的
`dataCompleteThrough`。若失败，保留固定错误码和计数证据，不要通过手工表更新绕过
checkpoint、日期锁或 attempt fence。
