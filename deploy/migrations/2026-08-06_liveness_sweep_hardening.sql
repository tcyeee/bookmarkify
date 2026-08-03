-- 活性巡检加固（第 1 部分／共 2 部分）：巡检轮次日志表、候选查询的新索引、归档记录的调度游标。
--
-- **本文件是纯增量的**：只建表、只建索引、只改 ARCHIVED 行的调度游标。当前线上的旧 API
-- 完全感知不到它，所以可以（也应该）在部署新 API **之前**就应用。
--
-- ⚠️ 之所以要提前应用：`bookmark_sweep_log` 是新代码每跑完一轮巡检就写一行的地方。那次
-- 写入包在 runCatching 里（表不存在只打一条 warn，不影响巡检本身），所以晚了不会把系统跑挂
-- —— 但那张表正是熔断唯一的持久化现场，晚一天就少一天证据。
--
-- 删除旧索引的部分拆在 `2026-08-06b_liveness_sweep_drop_old_index.sql`，**要等新 API 部署完**
-- 再执行：旧 API 的候选查询还指着 idx_bookmark_next_check。先建后删，与本仓库既有纪律一致。
--
-- 幂等：全部 IF NOT EXISTS。
--
-- 应用记录：2026-08-06 已在生产库执行（当时 bookmark 共 13 行，ARCHIVED 0 行）。

-- ── 0. 巡检轮次日志 ──────────────────────────────────────────────────────────
--
-- `bookmark_ping_log` 记的是单次探测，回答不了"这一轮整体怎么样"。而巡检最重要的那个
-- 信号——熔断（判定"我方链路坏了、本轮全表结论不可信"因而中止）——此前唯一的出口是一行
-- log.error。日志会滚动、没人盯着，等于没有留痕。落成一行数据之后，"最近一天熔断过
-- 几次""积压是不是一直追不上""有多少重新抓取因队列拥堵被推迟"才都成为一句 SQL。
CREATE TABLE IF NOT EXISTS public.bookmark_sweep_log (
    id                           varchar(64) NOT NULL,
    -- 哪个巡检任务：retryUnreachableBookmarks / livenessCheckStaleBookmarks / reviveArchivedBookmarks
    task_label                   varchar(64) NOT NULL,
    -- 本轮实际处理的候选数（已按 LIMIT 截断、已过滤非域名类型）
    candidates                   integer DEFAULT 0 NOT NULL,
    -- 到期候选总数，不含 LIMIT。持续大于 candidates 即说明检测间隔配置追不上数据量
    backlog                      bigint DEFAULT 0 NOT NULL,
    probed                       integer DEFAULT 0 NOT NULL,
    short_circuited              integer DEFAULT 0 NOT NULL,
    alive_count                  integer DEFAULT 0 NOT NULL,
    dead_count                   integer DEFAULT 0 NOT NULL,
    unknown_count                integer DEFAULT 0 NOT NULL,
    triggered_parse              integer DEFAULT 0 NOT NULL,
    -- 想重新抓取但因解析队列余量不足被推迟到下一轮的条数
    deferred_parse               integer DEFAULT 0 NOT NULL,
    -- 非空即"本轮被熔断中止，没有改动任何书签"
    breaker_reason               varchar(500),
    duration_ms                  bigint DEFAULT 0 NOT NULL,
    create_time                  timestamp DEFAULT now() NOT NULL,
    CONSTRAINT bookmark_sweep_log_pkey PRIMARY KEY (id)
);
-- 两种查法：按时间倒序翻最近的轮次；只看熔断过的轮次（部分索引，非空行本就是少数）
CREATE INDEX IF NOT EXISTS idx_sweep_log_create_time
    ON public.bookmark_sweep_log USING btree (create_time DESC);
CREATE INDEX IF NOT EXISTS idx_sweep_log_breaker
    ON public.bookmark_sweep_log USING btree (create_time DESC) WHERE breaker_reason IS NOT NULL;

-- ── 1. 候选查询的索引 ────────────────────────────────────────────────────────
--
-- 巡检的候选条件是「某个 parse_status + 调度游标已到期」，每小时跑两轮（各一次 count
-- 加一次 select）。原来的 idx_bookmark_next_check 有三个问题：
--
--   a) parse_status 不是索引列，只是一个 `<> 'PENDING'` 的部分索引条件。于是
--      SUCCESS / UNREACHABLE / ARCHIVED 三类记录混在同一棵树里，两个巡检各自都要
--      扫过对方的行再过滤掉。
--   b) 归档记录的 next_check_at 此前写完就再不推进，永远停在过去，于是它们**永久堆积
--      在扫描区间的最前端**，且只增不减 —— 每一轮都要先趟过这批尸体。
--      （代码侧同步修掉了：归档时把游标推到 30 天后的复活探测周期上。）
--   c) 谓词是 `next_check_at <= ? OR next_check_at IS NULL`，排序是
--      `ASC NULLS FIRST`；而 btree 升序默认 NULLS LAST。OR 不可 sarg、排序也用不上
--      索引，结果是每轮把该状态下的全部记录排一遍。
--
-- 新索引把 parse_status 提为前导列，并对齐代码里改用的 COALESCE 表达式。
CREATE INDEX IF NOT EXISTS idx_bookmark_due_check
    ON public.bookmark (parse_status, COALESCE(next_check_at, TIMESTAMP '1970-01-01'));

-- 旧索引的 DROP **不在这个文件里** —— 见 2026-08-06b。当前线上跑的还是旧 API，
-- 它的候选查询仍然指着 idx_bookmark_next_check。先建新的、后删旧的，与本仓库
-- 「建表 → 部署 → 再 drop」的既有纪律一致。

-- ── 2. 把存量归档记录的游标挪到复活探测周期上 ────────────────────────────────
--
-- 这些记录的 next_check_at 停在过去。新加的 reviveArchivedBookmarks（每天一轮，
-- 批量 50）会按游标顺序捞它们，散开到 30 天窗口里而不是让它们全部一次性到期。
UPDATE public.bookmark
SET next_check_at = now() + (random() * INTERVAL '30 days')
WHERE parse_status = 'ARCHIVED'
  AND (next_check_at IS NULL OR next_check_at <= now());
