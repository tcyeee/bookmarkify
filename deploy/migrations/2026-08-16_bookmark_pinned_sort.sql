-- 2026-08-16 — bookmark 补 pinned_sort 一列：置顶区的人工排序
--
-- 应用时机：**部署 API 之前**，而且这一条比同批别的迁移更硬 —— 列名直接写在
-- `BookmarkMapper.allBookmarkByUid` 的 SELECT 里，那是桌面布局(`/bookmark/query`)唯一的取数口。
-- 列不存在不是「少一个字段」，是**整个查询报错**，用户打开首页什么书签都看不到。
--
-- 为什么不复用现成的排序表：桌面顺序在 `user_preference.node_sort_map_json` 里，key 是布局节点 id，
-- 表达的是「某节点在它所属的那一层里排第几」。置顶区把分散在各文件夹里的书签抽出来平铺成一行，
-- 这些书签**跨层**，它们之间的先后在那张表里无从表达（各自的 sort 只在自己那一层内有意义）。
-- 硬用它来排还有个更糟的副作用：拖动置顶区会连带把书签在它自己文件夹里的位置也挪了。
--
-- 因此这一列与 pinned 同处 bookmark（用户的一次收藏）层：置顶与置顶顺序本就是一件事的两面。
--
-- NOT NULL DEFAULT 0 而不是可空：这里的「没有值」没有独立含义 —— 一条从没排过序的置顶书签就是
-- 排在最前的那一档，0 正是它。历史行全部取 0，于是首次加载时置顶区退回按 bookmark_id 的稳定次序，
-- 用户拖一次就写实了。PostgreSQL 11 起加带默认值的列不重写全表，这张表再大也是瞬时的。
--
-- **不加索引**：置顶集合是按 uid 取回全部书签后在内存里筛出来的（layout 本来就要全量），
-- 没有任何一条查询以 pinned_sort 为条件或排序键落到数据库。
--
-- 幂等，可重复执行。

ALTER TABLE public.bookmark ADD COLUMN IF NOT EXISTS pinned_sort integer DEFAULT 0 NOT NULL;
COMMENT ON COLUMN public.bookmark.pinned_sort IS
    '置顶区中的排列顺序，越小越靠前。仅在 pinned = true 时有意义；取消置顶不清零（重新置顶会重新分配到末尾）。'
    ' 与桌面树的排序(user_preference.node_sort_map_json)彼此独立：那张表按层排，置顶区是跨层平铺的。';
