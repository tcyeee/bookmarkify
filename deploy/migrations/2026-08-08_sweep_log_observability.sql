-- 2026-08-08 — sweep_log 补两列观测字段
--
-- 应用时机：**部署 API 之前**。新代码每轮巡检都会写这两列，列不存在时 recordSweepRound 的
-- runCatching 只会打一行 warn —— 巡检本身照常跑，代价是整段窗口内一行轮次记录都不落，而那正是
-- 「最近一天熔断过几次」「巡检还在不在跑」唯一的数据来源（后台常驻告警条读的就是它）。
--
-- 两列都可空，不给默认值：2026-08-08 之前的历史行确实**没有**这个信息，填 0 会被读成
-- 「上限为 0」「短路里一条 DEAD 都没有」，两个都是错的结论。后台按 null 显式退回旧的展示方式。
--
-- 幂等，可重复执行。

ALTER TABLE public.sweep_log ADD COLUMN IF NOT EXISTS batch_size integer;
COMMENT ON COLUMN public.sweep_log.batch_size IS
    '本轮的单次处理上限(LIMIT)。判断「检测间隔追不上数据量」要拿 backlog 和它比，而不是和 candidates 比'
    ' —— 后者还扣掉了本地地址/IP 这类不该探测的记录，只要混进一条就会让 backlog > candidates 恒成立。null=历史行';

ALTER TABLE public.sweep_log ADD COLUMN IF NOT EXISTS short_circuited_dead integer;
COMMENT ON COLUMN public.sweep_log.short_circuited_dead IS
    '短路的那部分里结论为 DEAD 的条数（其余为 UNKNOWN，不会有 ALIVE）。dead_count/unknown_count 是'
    ' 「探测+短路复用」的合计，而熔断判据的分母只有真正探测过的那部分，缺了这个数就还原不出熔断看到的比例。null=历史行';
