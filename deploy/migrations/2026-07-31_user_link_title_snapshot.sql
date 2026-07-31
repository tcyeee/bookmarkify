-- 抹掉 bookmark_user_link 里「创建时从 bookmark 拷来的标题/描述快照」，让 NULL 重新表示
-- 「用户没改过」。详见根目录 SITE_LAYERING_DESIGN.md §6。必须在部署新版 API **之后**执行。
--
-- 修的是什么：BookmarkUserLink 的构造函数在创建关联记录时把 bookmark.title / description 拷了
-- 一份进来。于是「用户手改的标题」和「创建时的快照」在数据上完全不可区分，代码永远判断不出
-- 新一次抓取该不该覆盖它 —— 页面改版后用户的标题要么永远是旧的、要么被静默冲掉，取决于当时
-- 走了哪条代码路径。留 NULL 之后覆盖策略是显然的：NULL 用页面标题，非 NULL 是用户的、永不覆盖。
--
-- **执行顺序与其他几个迁移相反：这一个必须在部署之后跑。**
-- 旧代码读的是 COALESCE(a.title, b.title)，抹成 NULL 它照样能回落到页面标题，不会显示空白；
-- 但旧代码**写**的时候仍会拷快照，先跑迁移只会被随后的新增记录重新污染。放在部署之后跑，
-- 抹掉的就是全部存量快照，而此后不再产生新的。
--
-- 判据是「与关联页面的当前值逐字相同」。这必然有误差，两个方向都有：
--   * 漏抹：用户手改后又被页面标题追上（改成了和页面一样的值）→ 判成快照被抹掉。
--     后果只是这条书签回到"跟随页面标题"，而用户看到的文案不变（本来就一样）。
--   * 误抹：用户从未改过、但页面标题在这之后变了 → 快照与当前值不同，判成"用户改过"而保留。
--     后果是这条书签的标题停在旧值不再跟随 —— 与迁移前的行为完全一致，不算退步。
-- 没有更好的判据可用：区分这两者所需的信息（谁写的、什么时候写的）从来没被记录过。
-- 所以这里刻意只抹**能确定是快照**的那一部分，宁可漏抹不误抹。

BEGIN;

-- 先看一眼影响面（可单独执行，不改数据）：
-- SELECT count(*) FILTER (WHERE ul.title IS NOT NULL) AS has_title,
--        count(*) FILTER (WHERE ul.title = b.title)   AS title_is_snapshot,
--        count(*) FILTER (WHERE ul.description IS NOT NULL)     AS has_desc,
--        count(*) FILTER (WHERE ul.description = b.description) AS desc_is_snapshot
--   FROM bookmark_user_link ul JOIN bookmark b ON b.id = ul.bookmark_id;

UPDATE bookmark_user_link ul
   SET title = NULL
  FROM bookmark b
 WHERE b.id = ul.bookmark_id
   AND ul.title IS NOT NULL
   AND ul.title = b.title;

UPDATE bookmark_user_link ul
   SET description = NULL
  FROM bookmark b
 WHERE b.id = ul.bookmark_id
   AND ul.description IS NOT NULL
   AND ul.description = b.description;

COMMENT ON COLUMN bookmark_user_link.title IS
    '用户自己写的标题；NULL 表示没改过(而不是"改成了空")，抓取值永不覆盖非 NULL 的值';
COMMENT ON COLUMN bookmark_user_link.description IS
    '用户自己写的备注；NULL 表示没改过';

COMMIT;

-- 验证：
-- 1) 应当没有「与页面标题逐字相同」的残留快照
-- SELECT count(*) FROM bookmark_user_link ul JOIN bookmark b ON b.id = ul.bookmark_id
--  WHERE ul.title = b.title;
-- 2) 剩下的非 NULL 值就是"用户真的改过"的集合，抽查一下是否合理
-- SELECT ul.title AS user_title, b.title AS page_title
--   FROM bookmark_user_link ul JOIN bookmark b ON b.id = ul.bookmark_id
--  WHERE ul.title IS NOT NULL LIMIT 20;
