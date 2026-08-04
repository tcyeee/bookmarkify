-- 2026-08-07 清理导入重复占位遗留的无源磁贴
--
-- 这是一次**数据修复**，不改结构。修的是 2026-08-04 线上那批记录，成因见同批代码改动
-- （BookmarkServiceImpl.discardDuplicatePlaceholder）：
--
--   导入路径把占位行绑到 canonical 页面走的是 UPDATE (resetPageId)，撞上 uk_bookmark_uid_page
--   唯一键时没人接，异常被事件监听器的 runCatching 吞掉，节点原样停在 BOOKMARK_LOADING，
--   drainStuckLoading 每 5 分钟重投一次、再撞同一个唯一键。最终 dispatch_attempts 耗尽，由
--   terminateExhaustedLoading 按「这个网址永远抓不成书签」收口成**无源书签**（page_id = NULL）。
--
-- 于是用户桌面上同一个网址有两个磁贴：一个正常（page_id 指向真实页面），一个没图标没标题
-- （page_id IS NULL）。后者纯属这个 bug 的产物，本应在撞唯一键的那一刻就被丢弃。
--
-- 应用时机：**部署新 API 之后**，顺序无关紧要但没有必要抢跑 —— 新代码消除的是继续产生这类行的
-- 途径，已经产生的这些不会自愈（它们已不在 BOOKMARK_LOADING 状态，所有对账任务都不会再碰）。
--
-- 幂等：可重复执行，第二次匹配不到任何行。

BEGIN;

-- ────────────────────────────────────────────────────────────────
-- 判据
-- ────────────────────────────────────────────────────────────────
--
-- 只删「同一用户、同一原始网址下，既有一条 page_id 非空的行、又有一条 page_id 为空的行」中的后者。
--
-- 三个限定缺一不可：
--   * page_id IS NULL 本身是**合法状态**，含义是「确定没有 canonical 记录」——真正解析不出来的
--     javascript: 小书签、about: 页面就长这样，它们是用户书签栏里的正常内容，绝不能一起删掉。
--     区分它们和本次的受害者，靠的正是「同一个网址还存在一条绑好了的兄弟行」。
--   * 按 url_full 逐字比对而不是 canonical 四元组：无源行根本没有 canonical 记录可比，
--     它身上只有用户导入时给的原始网址。而这个 bug 的成因就是同一份导入文件里出现了两遍
--     **同一个字符串**，逐字比对恰好就是它的特征。
--   * deleted = false：用户自己删掉的行不参与判定，也不被删（本项目没配逻辑删除，
--     deleted 全靠各查询手写过滤，漏掉它会把已删除的行当成"兄弟行"用）。

CREATE TEMPORARY TABLE tmp_dup_placeholder ON COMMIT DROP AS
SELECT dup.id AS bookmark_id, dup.layout_node_id
FROM public.bookmark dup
WHERE dup.page_id IS NULL
  AND dup.deleted = false
  AND EXISTS (
      SELECT 1
      FROM public.bookmark ok
      WHERE ok.uid = dup.uid
        AND ok.url_full = dup.url_full
        AND ok.page_id IS NOT NULL
        AND ok.deleted = false
  );

-- 留痕：执行时把这行的输出记下来，它是这次修复唯一的证据
SELECT count(*) AS "将删除的无源重复磁贴数" FROM tmp_dup_placeholder;

-- 关联行与布局节点必须一起删。只删其一的话：留下的节点会成为一个 typeApp 为空的
-- 幽灵磁贴（layout() 照样把它渲染出来），留下的关联行则再也没有节点能引用到。
DELETE FROM public.user_layout_node n
USING tmp_dup_placeholder t
WHERE n.id = t.layout_node_id;

DELETE FROM public.bookmark b
USING tmp_dup_placeholder t
WHERE b.id = t.bookmark_id;

-- 刻意**不**动 user_preference 里的排序 JSON：那张图里残留几个已不存在的 node id 是无害的
-- （layout() 按真实节点构树，排序表只是查表用），而且常规的删除书签路径同样不清它，
-- 在这里单独清反而引入一条别处没有的行为分支。

COMMIT;

-- ────────────────────────────────────────────────────────────────
-- 执行后自检：应当返回 0 行
-- ────────────────────────────────────────────────────────────────
-- SELECT b.uid, b.url_full, count(*)
-- FROM public.bookmark b
-- WHERE b.deleted = false
-- GROUP BY b.uid, b.url_full
-- HAVING count(*) FILTER (WHERE b.page_id IS NULL) > 0
--    AND count(*) FILTER (WHERE b.page_id IS NOT NULL) > 0;
