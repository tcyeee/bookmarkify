-- 2026-08-09 — page_ping_log 补 sweep_id，把「一轮巡检」和「这一轮探了哪些页面」接起来
--
-- 应用时机：**必须在部署 API 之前**，而且这一条比同批其它迁移都硬。
--
-- sweep_log 与 page_ping_log 此前没有任何关联列，后台点开一轮巡检只能看到七八个聚合数字，
-- 「这 180 条失联具体是哪些页面」无从下钻 —— 而那恰恰是熔断轮次唯一值得看的东西。
-- 唯一能凑合的替代是时间窗（sweep_log.create_time 是轮次结束时刻，减 duration_ms 得起点），
-- 但两个巡检任务各自加锁、彼此不互斥，锁 TTL 30 分钟，一轮跑久了时间窗就会重叠，而
-- page_ping_log 没有 task_label，重叠时根本拆不开。所以老老实实存 id。
--
-- 为什么"必须"在部署前：新代码写 ping 日志走的是 pingLogMapper.insert(...)，**没有** runCatching
-- 兜底（这批探测结果是判断"我方哪里坏了"的唯一现场，吞掉异常等于把现场也丢了）。列不存在时
-- 那条批量 insert 直接抛 SQL 异常，异常穿出 pingSweepExclusively —— 后果不是"少记一列"，而是
-- 整轮巡检中止：探测结果不落库、next_check_at 不推进、recordSweepRound 也走不到，
-- 于是后台的常驻告警条会看见「巡检已 N 小时没跑过」。
--
-- 可空、无默认值：迁移之前的历史行确实不属于任何已知轮次，填空串会被读成"属于某一轮但那轮没了"。
-- 后台对查不到明细的历史轮次显式提示，而不是画一个空列表让人以为是 bug。
--
-- 幂等，可重复执行。

ALTER TABLE public.page_ping_log ADD COLUMN IF NOT EXISTS sweep_id character varying(64);

COMMENT ON COLUMN public.page_ping_log.sweep_id IS
    '产生这次探测的巡检轮次(sweep_log.id)。null=2026-08-09 之前的历史行，或非巡检路径发起的探测。'
    ' 不做外键：sweep_log 与本表同为 90 天保留期、由 purgeExpired 各自清理，'
    ' 外键会让"轮次先被清掉"变成删除失败，而这里本来就允许悬空';

-- 下钻查询是 WHERE sweep_id = ? ORDER BY create_time：一轮最多 200 行，走这个索引直接定位。
-- 部分索引：绝大多数历史行的 sweep_id 是 null，没必要为它们建条目。
-- CONCURRENTLY 不能在事务块里执行，这一句要单独跑。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_ping_log_sweep
    ON public.page_ping_log (sweep_id, create_time)
    WHERE sweep_id IS NOT NULL;
