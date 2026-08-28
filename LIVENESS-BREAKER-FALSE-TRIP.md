# 活性巡检熔断器误触发

> 状态：**已定位，未修复** —— 2026-08-28 查生产日志时发现
> 范围：`LivenessSweepService` / `LivenessPolicy.breakerReason` / `livenessCheckStaleBookmarks`
> 关联：本轮同时修掉的两个 bug（`persistProbeResult` 的 `next_check_at` 双重赋值、`OssReconcileServiceImpl` 的 NPE）见提交记录；本文只讲第三个问题，它是**策略层的取舍**，不是一行 bug。

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
4. 后台「巡检健康」看板：`livenessCheckStaleBookmarks` 有 `sweep_log` 行、`breaker_reason` 非空，能看出在熔断，但没有任何告警会因「熔断连续 N 轮」而升级。

---

## 4. 候选修法

没有动手，先记选项。倾向 **C + A 组合**。

### A. 把长期 `UNKNOWN` 的域名 park 到更远的游标

仿 `parkNonDomain`：某域名连续 N 轮直接探测都是 `UNKNOWN`，就把它名下页面的 `next_check_at` 推到 7 天而不是退避曲线上的 1h。这样它们不再每轮霸占候选、不再顶熔断分母。

- 代价：`UNKNOWN` 目前**刻意不累加** `consecutiveFail`，需要新引入一个「连续 UNKNOWN 次数」计数（`site` 层最自然，与 `updateSiteLiveness` 的根探测同源）。
- 风险：反爬策略/出口 IP 可能变化，7 天一次复查可接受；不要设成永久。

### B. 把「已知反爬」的域名排除出熔断分母

`site` 层记录最近若干次 `/ping` 结论，某域名稳定回 403/406/412 即标记为「预期 UNKNOWN」。`breakerReason` 计算占比时只把**没有预期-UNKNOWN 历史**的域名计入分母。

- 代价：要在 `site` 上加一列历史 / 状态，`LivenessPolicy` 从纯函数变成要吃这个状态。
- 好处：真出我方故障时，这些域名一起变 `UNKNOWN` 也无所谓 —— 它们本来就按 `UNKNOWN` 处理。

### C. 提高 UNKNOWN 熔断的「最小域名数」门槛 —— 最小改动

现在 UNKNOWN 判据只要求 `total >= 10`（探测次数），对域名数没有下限。加一条：非-`ALIVE` 域名 `>= 4`（或 5）才允许这条判据触发。4 个域名里 2 个坏，不足以代表「系统性故障」。

- 纯 `LivenessPolicy` 改动 + 单测，零迁移、零新列。
- 代价：真正只有 2~3 个域名的部署遭遇我方故障时，这条判据要等域名更多才触发；但 `DEAD ≥ 90%` 那条判据、以及 `MIN_SAMPLE_UNKNOWN` 的探测次数下限都还在，兜底仍有。小部署本身可丢的东西也少。
- **这条能立刻止血**，A/B 可以之后再补。

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
