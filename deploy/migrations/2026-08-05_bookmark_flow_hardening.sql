-- 2026-08-05 添加书签链路加固
--
-- 三件事，都围绕 ADD-BOOKMARK-FLOW.md 那条链路：
--   1. 给 bookmark_user_link / user_layout_node 补索引 —— 这两张表此前**除主键外一个索引都没有**，
--      而链路上每一次读都落在它们身上（drainStuckLoading 每 30 秒扫一次全表）。
--   2. 用唯一索引接管「同一用户不能重复收藏同一页面」—— 此前挡住它的其实是 addOne 上那个 1 秒
--      的 @Throttle，而限流器在 Redis 故障时是**降级放行**的（ThrottleAspect），正确性不该挂在它上面。
--   3. 加 dispatch_attempts，给 drainStuckLoading 的补投递一个次数上限，消除队头阻塞。
--
-- 应用时机：**必须在部署 API 之前**。新代码会读写 bookmark_user_link.dispatch_attempts，
-- 列不存在时 drainStuckLoading 每 30 秒抛一次异常（补投递整个停摆，导入永远抓不完）。
--
-- 全文件幂等，可重复执行。

BEGIN;

-- ────────────────────────────────────────────────────────────────
-- 1. 索引
-- ────────────────────────────────────────────────────────────────
--
-- 下面前四条在部分环境里可能已经手工建过（架构审查之后先行补的那批），沿用**已有的名字**，
-- `IF NOT EXISTS` 会让它们变成空操作。刻意不另起一套命名：同一个索引定义挂两个名字，
-- 写放大要付两遍，而 EXPLAIN 里看到哪个纯属运气。

-- addOne 的第一道判重：WHERE uid = ? AND bookmark_id = ? AND deleted = false
-- 也覆盖 allBookmarkByUid / duplicateBookmarkIds / bookmarkIdsByUid 这些按 uid 起手的查询
CREATE INDEX IF NOT EXISTS idx_bul_uid_live
    ON public.bookmark_user_link (uid, bookmark_id)
    WHERE deleted = false;

-- findStuckLoading 的 JOIN 方向（n.id = a.layout_node_id）以及 showForDesktop 的回查
CREATE INDEX IF NOT EXISTS idx_bul_layout_node
    ON public.bookmark_user_link (layout_node_id);

-- drainStuckLoading 的主驱动。部分索引：BOOKMARK_LOADING 在稳态下是极小的子集
-- （只有正在抓取的那几条 + 导入积压），全表索引在这里是纯浪费。
CREATE INDEX IF NOT EXISTS idx_uln_loading
    ON public.user_layout_node (created_at)
    WHERE type = 'BOOKMARK_LOADING';

-- layout() —— 每次打开桌面都要按 uid 取回整棵树
CREATE INDEX IF NOT EXISTS idx_uln_uid
    ON public.user_layout_node (uid);

-- 文件夹展开 / 级联删除按 parent_id 取子节点
CREATE INDEX IF NOT EXISTS idx_uln_parent
    ON public.user_layout_node (parent_id)
    WHERE parent_id IS NOT NULL;

-- 后台「收录者」回填（fillOwners）按 bookmark_id 批量反查关联行。
-- 注意它的谓词只有 deleted = false，**比 idx_bul_unbound 宽**：那条带着
-- `AND bookmark_id = 'LOADING'`，只能服务导入占位的查询，服务不了这个反查。
CREATE INDEX IF NOT EXISTS idx_bul_bookmark
    ON public.bookmark_user_link (bookmark_id)
    WHERE deleted = false;

-- 上面那条建好之后 idx_bul_unbound 就是它的严格子集了（同一列、谓词更窄），
-- 规划器能用宽的那条服务 `bookmark_id = 'LOADING' AND deleted = false`。
-- 留着只是多付一份写放大，删掉。
DROP INDEX IF EXISTS public.idx_bul_unbound;

-- ────────────────────────────────────────────────────────────────
-- 2. 补投递重试计数
-- ────────────────────────────────────────────────────────────────

-- drainStuckLoading 每次补投递 +1；解析链路判定「我方抓取服务不可用」(E307) 时清零 ——
-- 我方故障不该消耗用户这条书签的重试预算。超过上限的行由 drainStuckLoading 就地终结成
-- 无源书签，不再无限占住 ORDER BY create_time 的队头把后面的行饿死。
ALTER TABLE public.bookmark_user_link
    ADD COLUMN IF NOT EXISTS dispatch_attempts integer DEFAULT 0 NOT NULL;

-- ────────────────────────────────────────────────────────────────
-- 3. 重复收藏的历史数据修复 + 唯一约束
-- ────────────────────────────────────────────────────────────────

-- 3.1 先把「已终结的无源书签」从 'LOADING' 改成 NULL。
--     finishNodeWithoutBookmark 只翻转了布局节点，关联行的 bookmark_id 一直留着 'LOADING'，
--     于是 assertNotPendingImport 会永远把它当成「还在导入队列里」，用户之后再添加同一个网址
--     会拿到一个假的 E126。语义收敛成：'LOADING' = 待绑定，NULL = 确定没有 canonical 记录。
UPDATE public.bookmark_user_link a
SET bookmark_id = NULL
WHERE a.bookmark_id = 'LOADING'
  AND EXISTS (
      SELECT 1 FROM public.user_layout_node n
      WHERE n.id = a.layout_node_id AND n.type <> 'BOOKMARK_LOADING'
  );

-- 3.2 修复历史重复：同一 (uid, bookmark_id) 保留最早的一条，其余连同它们的桌面节点一起移除。
--     这正是 E126 的语义在存量数据上补做一遍 —— 那些磁贴本来就不该存在（用户看到的是两个
--     一模一样的格子）。留着的话下面的唯一索引直接建不起来。
DO $$
DECLARE
    dup_count integer;
BEGIN
    WITH ranked AS (
        SELECT id,
               layout_node_id,
               row_number() OVER (
                   PARTITION BY uid, bookmark_id
                   ORDER BY create_time ASC, id ASC
               ) AS rn
        FROM public.bookmark_user_link
        WHERE deleted = false
          AND bookmark_id IS NOT NULL
          AND bookmark_id <> 'LOADING'
    ),
    losers AS (
        SELECT id, layout_node_id FROM ranked WHERE rn > 1
    ),
    -- 数据修改型 CTE 在 PostgreSQL 里一定会执行，即使没有被后面引用
    drop_nodes AS (
        DELETE FROM public.user_layout_node
        WHERE id IN (SELECT layout_node_id FROM losers)
        RETURNING id
    )
    UPDATE public.bookmark_user_link
    SET deleted = true
    WHERE id IN (SELECT id FROM losers);

    GET DIAGNOSTICS dup_count = ROW_COUNT;
    IF dup_count > 0 THEN
        RAISE NOTICE '[2026-08-05] 清理重复收藏 % 条(保留每组最早的一条，同时删除其桌面节点)', dup_count;
    END IF;
END $$;

-- 3.3 唯一约束。
--     排除三类行：软删的（用户删掉之后必须能重新添加）、导入占位（bookmark_id='LOADING'
--     是常量，同一用户可以有很多条）、无源书签（NULL —— PostgreSQL 里 NULL 在唯一索引中
--     互不相等，写在条件里只是把意图说明白）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_bul_uid_bookmark
    ON public.bookmark_user_link (uid, bookmark_id)
    WHERE deleted = false
      AND bookmark_id IS NOT NULL
      AND bookmark_id <> 'LOADING';

COMMIT;
