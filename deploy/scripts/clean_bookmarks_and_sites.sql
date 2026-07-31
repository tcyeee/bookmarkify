-- ============================================================================
-- ⚠️ 不可逆 清空系统中所有「书签」与「站点(site)」数据
--
-- 使用场景：契约/资产模型改动后需要全量重抓，或本地/测试环境重置。
-- 保留：user_info、user_preference(仅清空排序 JSON)、background_*、category 字典、
--       layout_node_function、system_config、access_token、admin_grid_config。
-- ============================================================================

BEGIN;

-- 表实际建在 public 下，不是 bookmarkify 下。
-- 这里原本写的是 `SET search_path TO bookmarkify`，而生产库压根没有这个 schema
-- （`SELECT nspname FROM pg_namespace` 只有 public）—— 那样执行会在第一条 TRUNCATE 就
-- 报 relation does not exist。根 CLAUDE.md 里「PostgreSQL (schema: bookmarkify)」
-- 同样是错的，那是库名而非 schema 名。
SET search_path TO public;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 站点数据(site_*)：抓取事实 + 人工显示偏好
-- ─────────────────────────────────────────────────────────────────────────────
-- site_display_pref 是人工调过的内边距/背景色，正常重抓时绝不该动它；
-- 但这里是「连书签一起删」，书签没了偏好也就成了孤儿行，必须一起清。
TRUNCATE TABLE scrape_snapshot;
TRUNCATE TABLE site_page_meta;
TRUNCATE TABLE site_asset;
TRUNCATE TABLE site_display_pref;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 书签本体与用户关联
-- ─────────────────────────────────────────────────────────────────────────────
TRUNCATE TABLE bookmark_category;      -- 书签↔分类关联(category 字典本身保留)
TRUNCATE TABLE bookmark_user_link;     -- 用户的个人书签副本
TRUNCATE TABLE bookmark;               -- canonical 书签(urlHost + urlPath 去重)

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. 书签分享
-- ─────────────────────────────────────────────────────────────────────────────
-- user_share_bookmark 指向 bookmark_user_link.id，源没了分享必然空壳，一并清。
TRUNCATE TABLE user_share_bookmark;
TRUNCATE TABLE user_share;

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. 抓取/存活性日志
-- ─────────────────────────────────────────────────────────────────────────────
-- 纯统计用，与业务无引用。想保留历史排障数据的话，把这两行注释掉即可。
TRUNCATE TABLE bookmark_ping_log;
TRUNCATE TABLE scrapper_call_log;

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. 桌面布局树
-- ─────────────────────────────────────────────────────────────────────────────
-- 只删书签类节点与书签文件夹，保留 FUNCTION 节点(设置等系统功能项)，
-- 否则 layout_node_function 会留下指向已删节点的孤儿行。
DELETE FROM user_layout_node
WHERE type IN ('BOOKMARK', 'BOOKMARK_LOADING', 'BOOKMARK_DIR');

-- 清理 layout_node_function 中已失去宿主节点的行(防御性，正常应为 0 行)
DELETE FROM layout_node_function f
WHERE NOT EXISTS (SELECT 1 FROM user_layout_node n WHERE n.id = f.layout_node_id);

-- 排序信息是一份 {nodeId: sort} 的 JSON，节点删完后全是失效 key，重置为空。
UPDATE user_preference
SET node_sort_map_json = NULL,
    update_time        = now()
WHERE node_sort_map_json IS NOT NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 核对：以下所有 cnt 应为 0
-- ─────────────────────────────────────────────────────────────────────────────
SELECT 'bookmark'             AS t, count(*) AS cnt FROM bookmark
UNION ALL SELECT 'bookmark_user_link',   count(*) FROM bookmark_user_link
UNION ALL SELECT 'bookmark_category',    count(*) FROM bookmark_category
UNION ALL SELECT 'site_asset',           count(*) FROM site_asset
UNION ALL SELECT 'site_page_meta',       count(*) FROM site_page_meta
UNION ALL SELECT 'site_display_pref',    count(*) FROM site_display_pref
UNION ALL SELECT 'scrape_snapshot',      count(*) FROM scrape_snapshot
UNION ALL SELECT 'user_share',           count(*) FROM user_share
UNION ALL SELECT 'user_share_bookmark',  count(*) FROM user_share_bookmark
UNION ALL SELECT 'layout_node(bookmark)', count(*) FROM user_layout_node
          WHERE type IN ('BOOKMARK', 'BOOKMARK_LOADING', 'BOOKMARK_DIR');

COMMIT;
