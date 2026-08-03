-- 活性巡检加固（第 2 部分／共 2 部分）：删除被取代的旧索引。
--
-- ⚠️ **必须等新 API 部署完成之后**再执行。
--
-- 拆成单独一个文件不是洁癖：`idx_bookmark_next_check` 是**当前线上旧 API** 候选查询用的
-- 索引，而新查询用的是 `idx_bookmark_due_check`（第 1 部分已建好）。在部署之前删掉它，
-- 就等于让旧 API 在这段窗口里对 bookmark 做顺序扫描。反过来，部署之后它就是一棵没人读、
-- 却每次写入都要维护的树。所以顺序只能是：建新 → 部署 → 删旧。
--
-- 与本仓库既有的「create tables → deploy → full re-crawl → 只在那之后才跑对应的 drop」
-- 是同一条纪律。
--
-- 幂等：全部 IF EXISTS。

-- 被 idx_bookmark_due_check (parse_status, COALESCE(next_check_at, epoch)) 取代。
-- 旧定义 (next_check_at) WHERE parse_status <> 'PENDING' 的三个问题见第 1 部分的注释。
DROP INDEX IF EXISTS public.idx_bookmark_next_check;

-- 顺手清理一对完全相同的重复索引：idx_bookmark_pending 与 idx_bookmark_pending_stale
-- 的定义逐字相同（同一列表达式、同一部分索引条件）。重复索引不会算错任何结果，
-- 只是让每次写入多维护一棵树。
--
-- 注：2026-08-06 核对生产库时，那边只有 idx_bookmark_pending，并没有 _stale ——
-- 重复的那条只存在于 deploy/schema.sql 的快照里。这条 DROP 因此在生产是空操作，
-- 保留它是为了让别的环境（本地/新搭的库）也能收敛到同一个状态。
DROP INDEX IF EXISTS public.idx_bookmark_pending_stale;
