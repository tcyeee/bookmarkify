-- 让 canonical key 包含 query 与路由型 fragment，bookmark 从此是**纯页面级**记录。
-- 详见根目录 SITE_LAYERING_DESIGN.md §4。必须在 2026-07-31_site_table.sql 之后执行。
--
-- 修的是什么：canonical key 一直是 (url_host, url_path)，query 被整个丢掉
-- （WebsiteParser 解析出了 urlQuery，但全项目只用于 log.debug）。后果：
--   * youtube.com/watch?v=A 和 ?v=B 收敛成同一条 bookmark；
--   * 抓取目标 rawUrl = scheme://host+path = https://www.youtube.com/watch，不是任何一个视频；
--   * 第二个用户添加时 checkFlag() 在 24h 内返回 false，直接复用上一次的错误结果。
-- 所以库里所有带参数的深链，标题/描述/og 图都不是「旧」，是**错**。
--
-- 关于唯一索引的安全性：现有行的 url_query / url_fragment 全部回填为 ''，而
-- (url_host, url_path) 本来就是唯一的、site_id 与 url_host 又是一一对应，
-- 所以新索引在存量数据上不可能冲突，**不需要预先做去重**。
-- 真正需要善后的是「一行 bookmark 其实对应 N 个真实 URL」这件事，它无法用 SQL 修（丢掉的 query
-- 只存在于 bookmark_user_link.url_full 里），交给代码侧的修复任务处理，见文末查询与 §8 第 4 步。

BEGIN;

ALTER TABLE bookmark
    ADD COLUMN IF NOT EXISTS url_query    varchar(1000) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS url_fragment varchar(500)  NOT NULL DEFAULT '';

COMMENT ON COLUMN bookmark.url_query IS
    '规范化后的 query(已剥离追踪参数并按 key 排序)，参与 canonical key；无参数存空串';
COMMENT ON COLUMN bookmark.url_fragment IS
    '仅路由型 fragment(#/… 与 #!…)，页内锚点一律丢弃；单独成列以便无歧义拼回 rawUrl';

-- 刻意**不**新增 page_alive 列：bookmark 一行就是一个页面，现有的 is_activity 本来就是页面级的，
-- 再加一列同义的 page_alive 只会得到两列永远要同步写的重复状态。
-- 这次分层新增的只有站点级那一份 site.is_alive（上一个迁移已建），它由域名探测写；
-- is_activity 保持原义不变，由页面探测写。
COMMENT ON COLUMN bookmark.is_activity IS
    '页面级活性(这一个页面能否打开)；域名级活性在 site.is_alive';

-- 旧唯一约束必须先拿掉，否则 (host, /watch) 仍然唯一，v=A 与 v=B 永远无法拆成两行。
-- 索引名在建库时没进版本库（当年直接在生产库上建的），只能按「唯一 + 恰好这两列」反查；
-- 唯一约束的后备索引不能直接 DROP INDEX，所以要区分 constraint 与裸索引两种情况。
-- 若这段没匹配到任何东西，用 \d bookmark 人工确认后手工删除。
DO $$
DECLARE r record;
BEGIN
    FOR r IN
        SELECT i.indexname AS idx, c.conname AS con
          FROM pg_indexes i
          JOIN pg_class     ci ON ci.relname = i.indexname
          JOIN pg_namespace ns ON ns.oid = ci.relnamespace AND ns.nspname = i.schemaname
          LEFT JOIN pg_constraint c ON c.conindid = ci.oid
         WHERE i.tablename = 'bookmark'
           AND i.indexdef ILIKE '%UNIQUE%'
           -- 两种列序都认，历史索引怎么建的无从考证
           AND i.indexdef ~ '\((url_host|url_path),\s*(url_host|url_path)\)'
    LOOP
        IF r.con IS NOT NULL THEN
            RAISE NOTICE '删除旧唯一约束 %', r.con;
            EXECUTE format('ALTER TABLE bookmark DROP CONSTRAINT %I', r.con);
        ELSE
            RAISE NOTICE '删除旧唯一索引 %', r.idx;
            EXECUTE format('DROP INDEX %I', r.idx);
        END IF;
    END LOOP;
END $$;

-- 新的 canonical key。site_id 目前允许 NULL（旧代码还在跑，见上一个迁移的说明），
-- PostgreSQL 的唯一索引把 NULL 视为互不相等，所以这期间 site_id IS NULL 的行不受约束保护；
-- 收紧成 NOT NULL 在清理批次里做。
CREATE UNIQUE INDEX IF NOT EXISTS uk_bookmark_canonical
    ON bookmark (site_id, url_path, url_query, url_fragment);

COMMIT;

-- ─────────────────────────────────────────────────────────────────────────────
-- 验证
-- ─────────────────────────────────────────────────────────────────────────────
-- 1) 新索引已建、旧索引已删
-- SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'bookmark' AND indexdef ILIKE '%UNIQUE%';
--
-- 2) 需要拆分的行：一条 bookmark 底下的用户链接指向了多个不同的真实 URL。
--    这正是「v=A 与 v=B 被合成一条」的现场，代码侧修复任务要处理的就是这批。
-- SELECT b.id, b.url_host, b.url_path, count(DISTINCT ul.url_full) AS distinct_urls
--   FROM bookmark b JOIN bookmark_user_link ul ON ul.bookmark_id = b.id
--  WHERE ul.deleted = false
--  GROUP BY b.id, b.url_host, b.url_path
-- HAVING count(DISTINCT ul.url_full) > 1
--  ORDER BY distinct_urls DESC LIMIT 50;
--
-- 3) 受影响面有多大：带参数或带 hash 路由的用户链接总量
-- SELECT count(*) FILTER (WHERE url_full LIKE '%?%') AS with_query,
--        count(*) FILTER (WHERE url_full LIKE '%#/%' OR url_full LIKE '%#!%') AS with_hash_route,
--        count(*) AS total
--   FROM bookmark_user_link WHERE deleted = false;
