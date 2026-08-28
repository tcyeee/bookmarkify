# 活性巡检熔断器误触发

> 状态：**C + B + 连续熔断告警 + shouldRefreshContent 盲区 已实现** —— 2026-08-28。A 仍待办，见 §4。
> 范围：`LivenessSweepService` / `LivenessPolicy` / `ApiServiceImpl.pingWebsite` / `livenessCheckStaleBookmarks`
> 关联：本轮同时修掉的两个 bug（`persistProbeResult` 的 `next_check_at` 双重赋值、`OssReconcileServiceImpl` 的 NPE）见提交记录；本文只讲第三个问题，它是**策略层的取舍**，不是一行 bug。

---

## 已实现（2026-08-28）

**B：把 UNKNOWN 拆成「站点拒绝」与「我方链路没探到」。** `pingWebsite` 改返回
`PingProbeResult(outcome, siteRefusal)`，`siteRefusal` 由 `LivenessPolicy.isSiteRefusal` 判定
（拿到 403/406/412… 状态码 = 链路够得着源站）。`HostOutcome.ourChainInconclusive` 把这个维度
带进 `breakerReason`：
- **分子** = 整站都是「我方链路没探到」的域名；
- **分母** = 全部域名 − 整站都是「站点拒绝」的域名（永久反爬是常驻噪声，剔除；ALIVE 域名仍留在
  分母里正常稀释比例）。
落库三态、退避、判死一律不变——`siteRefusal` 只影响熔断分母和内容刷新闸门。

**C：`LivenessPolicy.MIN_UNKNOWN_HOSTS = 4`。** UNKNOWN 熔断判据的绝对下限——至少 4 个
「我方链路没探到」的去重域名，才谈得上「系统性故障」。纯 `LivenessPolicy` 改动，零迁移。
判据卡 `unknownHosts >= 4` 而非文档原稿的「非-ALIVE 域名 ≥ 4」。

**shouldRefreshContent 反爬盲区。** 该判据从 `outcome == ALIVE` 放宽到「ALIVE 或 站点拒绝的
UNKNOWN」：站点活着、只是反爬拦了 HEAD，而抓取链路有无头回退 + `siteapi.rs` 官方 API 救援，
能力严格更强。此前 B 站视频页 ping 恒 UNKNOWN，标题/封面会永久陈旧。

**连续熔断告警。** `SweepHealthVO` 新增 `maxConsecutiveBreaker` / `maxConsecutiveBreakerTask`
（按 `task_label` 分别数最新连续熔断轮数，取最大）。`SweepBreakerAlert.vue` 在连续 ≥ 3 轮
（`STUCK_BREAKER_ROUNDS`）时升级标题与描述文案，把「偶发抖动」和「自我维持的停摆」在告警条上
区分开——后者是把 2026-08-10 那类死锁从静默拖成多日事故的根因。

回归测试：`LivenessPolicyTest` 新增 `isSiteRefusal …` / `小体量部署里两个永久反爬域名不足以熔断`
/ `一批反爬域名 + 少量我方探不到` / `我方探不到的域名数没到下限…` / `…达到下限且过半就照常熔断`，
既有 breaker 用例按新语义调整（`ourChain = true/false` 显式区分）。

---

## 0. 一句话

小体量部署里，久未检查的候选只涉及三四个域名，其中两个（B 站、zfrontier）对机房出口 IP 是**永久性反爬**（412 / 403 → `UNKNOWN`）。`breakerReason` 的「UNKNOWN 域名占比 ≥ 50%」判据于是**每一轮都成立**，`livenessCheckStaleBookmarks` 半数轮次直接熔断中止，这批书签的活性与内容刷新永远推进不了 —— 而抓取服务本身是好的。

---

## 1. 生产实测

`sweep_log`，2026-08-27 18:00 → 2026-08-28 05:00（`livenessCheckStaleBookmarks`，整点跑）：

| create_time | candidates | probed | short_circuited | alive | dead | unknown | breaker_reason |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 05:00 | 0 | 0 | 0 | 0 | 0 | 0 | — |
| 04:00 | 13 | 11 | 2 | 6 | 2 | 5 | 无结论 **2/4** 个域名（共 11 次探测）已达 50%，抓取服务多半整体不可用 |
| 03:00 | 4 | 4 | 0 | 3 | 1 | 0 | — |
| 02:00 | 13 | 11 | 2 | 6 | 2 | 5 | 无结论 2/4 个域名… |
| 01:00 | 0 | 0 | 0 | 0 | 0 | 0 | — |
| 00:00 | 13 | 11 | 2 | 6 | 2 | 5 | 无结论 2/4 个域名… |
| 23:00 | 0 | 0 | 0 | 0 | 0 | 0 | — |
| 22:00 | 13 | 11 | 2 | 6 | 1 | 6 | 无结论 2/4 个域名… |
| … | | | | | | | 偶数点全部熔断，奇数点 0 候选 |

同一时段的健康信号，说明**抓取服务没坏**：

- `scrapper_call_log` 近 7 天 55 次调用、42 次成功，失败都是站点侧（`FETCH_FAILED` / `RECENTLY_FAILED`）。
- scraper 容器 `/ping` 日志：`www.bilibili.com` HEAD 141ms 回 412、`www.zfrontier.com` HEAD 54ms 回 403、`qqe2.com` 回 403 —— 都是**秒回**的确定性拒绝，不是超时、不是连不上。
- API 容器 9 天 0 重启，内存 450MB / 3.2GB。

### 奇偶点的锯齿

熔断分支对**已探测的 11 条**调 `PageScheduleWriter.protect`，游标推 `PROTECT_HOURS = 1h`。于是 04:00:10 熔断 → 11 条推到 ~05:00:10 → 05:00:00 那轮跑时它们还差 10 秒到期 → `candidates = 0` → 06:00:00 又全部到期 → 再次熔断。**自我维持，无自愈路径**，形态与 `LivenessPolicy` KDoc 里记的 2026-08-10 生产死锁完全一样，只是这次每轮之间隔了 1h、没有 25 轮字节级相同那么刺眼。

---

## 2. 根因

`LivenessPolicy.breakerReason` 的第一条判据：

```
total >= MIN_SAMPLE_UNKNOWN(10) && unknownHosts * 100 >= hosts * UNKNOWN_PERCENT(50)
```

- `hosts` = 本轮探测涉及的**去重域名数** = 4
- `unknownHosts` = 全部页面都判 `UNKNOWN` 的域名数 = 2（B 站所有内容页 412、zfrontier 403）
- `2 * 100 >= 4 * 50` → `200 >= 200` → **恒真**

域名去重（2026-08-10 加的缓解）在这里不起作用：分母本来就只有 4。而这 2 个 `UNKNOWN` 域名的 `UNKNOWN` 是**设计上就该长期为 `UNKNOWN`** 的 —— `outcomeOf` 把 403/406/412 明确映射成 `UNKNOWN`，理由正是「机房 IP 被反爬，判死等于拿我方网络位置给用户书签定罪」。

所以这是两条正确规则的对撞：

1. `outcomeOf`：反爬站点 → `UNKNOWN`（长期、确定性）。
2. `breakerReason`：`UNKNOWN` 域名过半 → 判定「抓取服务整体不可用」，熔断。

在域名基数小、且其中反爬站点占比高的部署里，(1) 的长期 `UNKNOWN` 被 (2) 读成了「我方故障」的证据。

---

## 3. 后果

1. **这 13 条书签的活性判定与内容刷新永久停摆。** 30 天的 `contentRefreshIntervalDays` 周期对它们不再推进。
2. **批次里真正失联的页面（`cron.qqe2.com`、`www.pansoso.com`）在这条任务里永远不会被确认 `DEAD` / 归档** —— 熔断在 `mayConfirmDeath` 分支之前就 `return` 了。（`retryUnreachableBookmarks` 本该兜底，但它被 `next_check_at` 双重赋值 bug 打死了 3 天，那个已随本轮修复。）
3. **每 2 小时白打 11 次 `/ping`**，产出零可用结论。
4. 后台「巡检健康」看板：`livenessCheckStaleBookmarks` 有 `sweep_log` 行、`breaker_reason` 非空，能看出在熔断，但没有任何告警会因「熔断连续 N 轮」而升级。✅ 已修（连续熔断告警）

---

## 4. 候选修法

**B / C / D-的思路 / 告警 均已实现（见「已实现」段）。** 仅 A 仍待办，视上线后 `sweep_log` 观察再定。

### A. 把长期 `UNKNOWN` 的域名 park 到更远的游标 —— 待办

仿 `parkNonDomain`：某域名连续 N 轮直接探测都是「站点拒绝」的 UNKNOWN，就把它名下页面的 `next_check_at` 推到 7 天而不是退避曲线上的 1h。这样它们不再每轮霸占候选。

- B 落地后这已不是**正确性**问题（它们已被剔出熔断分母），只是省掉每轮几次白探。
- 代价：需要新引入一个「连续 站点拒绝 次数」计数（`site` 层最自然，与 `updateSiteLiveness` 的根探测同源）。`PingProbeResult.siteRefusal` 已经把信号备好了，缺的只是持久化的计数列。
- 风险：反爬策略/出口 IP 可能变化，7 天一次复查可接受；不要设成永久。

### B. 把「站点拒绝」的 UNKNOWN 排除出熔断分母 —— ✅ 已实现（内存版）

原稿设想在 `site` 加一列历史。实际实现不需要：探测那一刻 `outcomeOf(reachable, status, blocked)` 就知道这个 UNKNOWN 是站点属性（拿到 403/412 状态码）还是我方属性（blocked / 没状态码 / scrapper 挂）。把这个 bit 顺着 `PingProbeResult` → `HostOutcome.ourChainInconclusive` 在**内存流程**里传进 `breakerReason` 即可，不动枚举、不动 `page_ping_log`、不动前端。

### C. 提高 UNKNOWN 熔断的「最小域名数」门槛 —— 最小改动 ✅ 已实现

现在 UNKNOWN 判据只要求 `total >= 10`（探测次数），对域名数没有下限。加一条：`unknownHosts >= MIN_UNKNOWN_HOSTS(4)` 才允许这条判据触发。4 个域名里 2 个坏，不足以代表「系统性故障」。

- 纯 `LivenessPolicy` 改动 + 单测，零迁移、零新列。
- 实现时把判据从原稿的「非-`ALIVE` 域名 ≥ 4」改成「`unknownHosts` ≥ 4」——前者会把 DEAD 域名算进来，`2 UNKNOWN + 2 DEAD` 又恒真。
- 代价：真正只有 2~3 个域名的部署遭遇我方故障时，这条判据要等域名更多才触发；但 `DEAD ≥ 90%` 那条判据、`MIN_SAMPLE_UNKNOWN` 的探测次数下限，以及「UNKNOWN 本就不写 `UNREACHABLE`」都还在，兜底仍有。

### D. 熔断改为「只丢弃本轮 UNKNOWN 结论」，而非整轮 abort

`breakerReason` 命中时，对判 `ALIVE` / `DEAD` 的域名照常落库（那是事实），只跳过 `UNKNOWN` 的行。这样 6 条 `ALIVE` 能正常刷新、2 条 `DEAD` 能走确认流程。

- 语义变化较大：现在「先全批探测、再原子决定」是刻意设计（`LivenessPolicy` KDoc 有述），改了要重新论证。
- 单独做风险偏高，列在这里作参照。

### 依赖关系

- 本轮已修的 `next_check_at` 双重赋值 bug 是前置：修好后 `cron.qqe2.com` 能经 `retryUnreachableBookmarks` 归档、离开候选池，批次里的死站点数会下降一点。
- C 落地后建议复测一周 `sweep_log`，确认 `livenessCheckStaleBookmarks` 的熔断率回到接近 0。

---

## 5. 回归判据

- `LivenessPolicyTest` 增一个用例：4 域名、2 个全 UNKNOWN、11 次探测 → **不熔断**（对应 C）。
- 保留现有 4 个从 2026-08-10 生产批次构造的 `breakerReason` 用例，确认真正的我方故障仍能触发。
- 生产侧：`SELECT date_trunc('day',create_time), count(*) FILTER (WHERE breaker_reason IS NOT NULL), count(*) FROM sweep_log WHERE task_label='livenessCheckStaleBookmarks' GROUP BY 1 ORDER BY 1 DESC` —— 上线后一周熔断占比应显著下降。
