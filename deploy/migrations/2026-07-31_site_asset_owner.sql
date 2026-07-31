-- 图片资产与展示偏好按「站点 / 页面」分层。详见根目录 SITE_LAYERING_DESIGN.md §5。
-- 必须在 2026-07-31_site_table.sql 之后执行。
--
-- 修的是什么：favicon 和 logo 是**域名级**的东西，一个站点一套；但它们此前按 bookmark_id 存，
-- 也就是按页面存。同域名下 1000 个页面 → 1000 份一模一样的 favicon 行、1000 次下载+OSS 上传，
-- 管理员为图标调好的内边距/背景色也要在 1000 行上各调一次。
--
-- 分界线沿用现成的 role，不新造概念：
--   FAVICON / LOGO            → owner_type='SITE'  全站共享
--   SOCIAL(og:image) / SCREENSHOT → owner_type='PAGE'  每页不同，就是页面内容本身
--
-- 附带修掉一个既有 bug：site_display_pref 建表时没有 id 列，而 SiteDisplayPrefEntity 上标了
-- @TableId id，于是 MyBatis-Plus 的 insert 会带上一个不存在的列 —— 「首次为某书签保存展示偏好」
-- 这条路径从来没成功过（已有行走 update 分支，所以只在新行上暴露）。这里把列补上。

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. site_asset：加 owner 列并回填
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE site_asset
    ADD COLUMN IF NOT EXISTS owner_type varchar(10),
    ADD COLUMN IF NOT EXISTS owner_id   varchar(64);

COMMENT ON COLUMN site_asset.owner_type IS 'SITE(favicon/logo，全站共享) | PAGE(社交图/截图，每页不同)';
COMMENT ON COLUMN site_asset.owner_id   IS 'owner_type=SITE 时是 site.id，=PAGE 时是 bookmark.id';

UPDATE site_asset a
   SET owner_type = CASE WHEN a.role IN ('FAVICON', 'LOGO') THEN 'SITE' ELSE 'PAGE' END,
       owner_id   = CASE WHEN a.role IN ('FAVICON', 'LOGO') THEN b.site_id ELSE a.bookmark_id END
  FROM bookmark b
 WHERE b.id = a.bookmark_id
   AND a.owner_type IS NULL;

-- 挂在已被删除的 bookmark 上的孤儿资产行：没有 site_id 可归，直接清掉。
-- 它们本来也永远不会被读到（解析入口都是从 bookmark 出发的）。
DELETE FROM site_asset WHERE owner_type IS NULL;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. site_asset：去重
-- ─────────────────────────────────────────────────────────────────────────────
-- 同一站点下 N 个页面各自存了一份 favicon，改成按 site 归属之后它们就成了重复行，
-- 必须先收敛到一行，否则第 3 步的唯一索引建不起来。
--
-- 保留优先级：已落 OSS 的优先（storage_url 非空的那行数据更完整，热链行留着没价值）
-- → 首页抓来的优先（更可能是站点真正的图标）→ 最早抓到的优先（fetched_at 稳定可复现）。
DELETE FROM site_asset
 WHERE id IN (
    SELECT id FROM (
        SELECT a.id,
               row_number() OVER (
                   PARTITION BY a.owner_type, a.owner_id, a.extractor, a.resolved_url
                   ORDER BY (a.storage_url IS NOT NULL) DESC,
                            (b.url_path = '/' AND b.url_query = '' AND b.url_fragment = '') DESC,
                            a.fetched_at
               ) AS rn
          FROM site_asset a
          LEFT JOIN bookmark b ON b.id = a.bookmark_id
    ) ranked
     WHERE rn > 1
 );

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. site_asset：索引切到 owner
-- ─────────────────────────────────────────────────────────────────────────────
-- owner_type / owner_id 在**本迁移里保持可空**，收紧成 NOT NULL 放在配套的
-- `_not_null.sql` 里、部署之后再执行。原因：这两侧都会炸。
--   * 现在就 SET NOT NULL → 线上仍是旧代码，它 INSERT site_asset 不带这两列，
--     每一次抓取都会 NOT NULL 违约；
--   * 等部署后再整块跑 → 部署完到迁移之间，新代码要写这两列而列还不存在，同样每次抓取都失败。
-- 所以「加列 + 回填 + 索引」必须在部署前，「收紧约束」必须在部署后，中间那段两边都能跑。

-- bookmark_id 保留但不再是主键路径：SITE 归属的行上它只是「最初由哪个页面抓来的」这一条
-- 溯源信息，业务代码一律改读 owner_id。允许为空，好让以后不经页面直接抓站点图标成为可能。
ALTER TABLE site_asset ALTER COLUMN bookmark_id DROP NOT NULL;
COMMENT ON COLUMN site_asset.bookmark_id IS '最初由哪个页面的抓取带回来的(溯源用)；归属一律看 owner_type/owner_id';

DROP INDEX IF EXISTS idx_site_asset_unique;
DROP INDEX IF EXISTS idx_site_asset_role;
DROP INDEX IF EXISTS idx_site_asset_hash;

-- 同一归属下，同一出处 + 同一地址只留一条。并发「补齐缺失的站点图标」靠它挡住重复插入。
CREATE UNIQUE INDEX IF NOT EXISTS idx_site_asset_unique
    ON site_asset (owner_type, owner_id, extractor, resolved_url);
CREATE INDEX IF NOT EXISTS idx_site_asset_role
    ON site_asset (owner_type, owner_id, role, is_primary);
CREATE INDEX IF NOT EXISTS idx_site_asset_hash
    ON site_asset (owner_type, owner_id, content_hash);

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. site_display_pref：补 id 列 + 改挂 site
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE site_display_pref
    ADD COLUMN IF NOT EXISTS id      varchar(64),
    ADD COLUMN IF NOT EXISTS site_id varchar(40);

UPDATE site_display_pref p
   SET site_id = b.site_id
  FROM bookmark b
 WHERE b.id = p.bookmark_id
   AND p.site_id IS NULL;

-- 同上：同站点下多个页面各自调过偏好，现在要收敛成一份。
-- 保留优先级：首页调的优先（那才是站点图标的实际调试现场）→ 最近改的优先。
DELETE FROM site_display_pref
 WHERE ctid IN (
    SELECT ctid FROM (
        SELECT p.ctid,
               row_number() OVER (
                   PARTITION BY p.site_id, p.display_mode
                   ORDER BY (b.url_path = '/' AND b.url_query = '' AND b.url_fragment = '') DESC,
                            p.update_time DESC
               ) AS rn
          FROM site_display_pref p
          LEFT JOIN bookmark b ON b.id = p.bookmark_id
    ) ranked
     WHERE rn > 1
 );

-- 挂在已删除 bookmark 上的孤儿偏好行，无 site 可归
DELETE FROM site_display_pref WHERE site_id IS NULL;

UPDATE site_display_pref SET id = gen_random_uuid()::text WHERE id IS NULL;

-- 补上 id 列这一步顺带修掉一个既有 bug：SiteDisplayPrefEntity 上标着 @TableId id，而版本库里的
-- 建表语句从来没有这一列（生产库当年是手工加上的，DDL 没进仓库），于是在任何按仓库 DDL 搭起来的
-- 新环境上，MyBatis-Plus 的 insert 都会带上一个不存在的列。

-- 主键从 (bookmark_id, display_mode) 换成代理键 id。
--
-- **这一步必须在下面的 `bookmark_id DROP NOT NULL` 之前**：PostgreSQL 不允许对主键列去掉
-- NOT NULL（`ERROR: column "bookmark_id" is in a primary key`）。
--
-- 放在部署**前**是安全的：`id` 列生产库已经有了，而旧代码的实体本来就会带上它，
-- 所以旧代码的 insert 在换主键之后照样能过。`ADD PRIMARY KEY` 会自动把 id 置为 NOT NULL，
-- 不需要额外的 SET NOT NULL。
-- 约束名按「这张表的主键」反查，建库时的命名无从考证。
DO $$
DECLARE pk_name text;
BEGIN
    SELECT c.conname INTO pk_name
      FROM pg_constraint c
      JOIN pg_class t ON t.oid = c.conrelid
     WHERE t.relname = 'site_display_pref' AND c.contype = 'p';
    IF pk_name IS NOT NULL THEN
        RAISE NOTICE '替换 site_display_pref 主键 %', pk_name;
        EXECUTE format('ALTER TABLE site_display_pref DROP CONSTRAINT %I', pk_name);
    END IF;
END $$;

-- 幂等：重复执行时主键已经是 id 了，不要再加一遍
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
         WHERE t.relname = 'site_display_pref' AND c.contype = 'p'
    ) THEN
        ALTER TABLE site_display_pref ADD PRIMARY KEY (id);
    END IF;
END $$;

-- bookmark_id 让位给 site_id：旧代码还在写它，所以这里只放宽、不收紧。
ALTER TABLE site_display_pref ALTER COLUMN bookmark_id DROP NOT NULL;

COMMENT ON COLUMN site_display_pref.site_id     IS '所属站点；内边距/背景色/钉死的图都是站点级的，同域所有页面共享';
COMMENT ON COLUMN site_display_pref.bookmark_id IS '历史列：最初在哪个页面上调的(溯源用)，业务代码不再读';

-- 真正的业务唯一键。site_id 此刻仍可空，而 PostgreSQL 视 NULL 互不相等，所以旧代码在这段
-- 过渡期里写入的（site_id 为 NULL 的）行不受它约束 —— 这正是我们要的：不拦旧代码，只保证
-- 新代码写进来的行唯一。
CREATE UNIQUE INDEX IF NOT EXISTS uk_site_display_pref_owner
    ON site_display_pref (site_id, display_mode);

-- 只剩 site_id 的 NOT NULL 留给 `_not_null.sql`、部署之后执行：
-- 旧代码不写 site_id，现在收紧就会让它每次保存展示偏好都违约。

COMMIT;

-- ─────────────────────────────────────────────────────────────────────────────
-- 验证
-- ─────────────────────────────────────────────────────────────────────────────
-- 1) 归属分布应与 role 一一对应（SITE 只有 FAVICON/LOGO，PAGE 只有 SOCIAL/SCREENSHOT）
-- SELECT owner_type, role, count(*) FROM site_asset GROUP BY owner_type, role ORDER BY owner_type, role;
--
-- 2) 收敛效果：分层前后的行数对比（favicon 行数应从「页面数」降到「站点数」量级）
-- SELECT owner_type, count(*) AS rows, count(DISTINCT owner_id) AS owners
--   FROM site_asset GROUP BY owner_type;
--
-- 3) 没有悬空归属
-- SELECT count(*) FROM site_asset a WHERE a.owner_type='SITE'
--    AND NOT EXISTS (SELECT 1 FROM site s WHERE s.id = a.owner_id);
-- SELECT count(*) FROM site_asset a WHERE a.owner_type='PAGE'
--    AND NOT EXISTS (SELECT 1 FROM bookmark b WHERE b.id = a.owner_id);
--
-- 4) 展示偏好每个 (站点, 模式) 至多一行
-- SELECT site_id, display_mode, count(*) FROM site_display_pref
--  GROUP BY site_id, display_mode HAVING count(*) > 1;
