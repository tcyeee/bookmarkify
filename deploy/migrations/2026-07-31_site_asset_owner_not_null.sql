-- `2026-07-31_site_asset_owner.sql` 的后半段：收紧约束。**必须在部署新版 API 之后执行。**
--
-- 为什么要拆成两个文件：这批约束的两侧都会炸。
--   * 部署**前**就 SET NOT NULL → 线上仍是旧代码，它 INSERT site_asset 不带 owner_type/owner_id、
--     INSERT site_display_pref 不带 site_id，于是**每一次抓取、每一次保存展示偏好都会违约失败**；
--   * 等部署**后**再整块跑前半段 → 部署完到迁移之间，新代码要写 owner_type 而列还不存在，
--     同样每次抓取都失败。
-- 所以「加列 + 回填 + 去重 + 索引」必须在部署前（那些操作旧代码新代码都能容忍），
-- 「收紧约束」必须在部署后（此时只有新代码在写，它一定会带上这些列）。
--
-- 执行前请确认新版 API 已经起来并成功抓过至少一条书签，否则回滚部署时这些约束会挡住旧代码。

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. 兜底回填：部署前那段过渡期里，旧代码可能又插进了一批没有归属的行
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE site_asset a
   SET owner_type = CASE WHEN a.role IN ('FAVICON', 'LOGO') THEN 'SITE' ELSE 'PAGE' END,
       owner_id   = CASE WHEN a.role IN ('FAVICON', 'LOGO') THEN b.site_id ELSE a.bookmark_id END
  FROM bookmark b
 WHERE b.id = a.bookmark_id
   AND (a.owner_type IS NULL OR a.owner_id IS NULL);

-- 仍然归不了属的（关联 bookmark 已删 / site_id 还是空）直接清掉：解析入口都从 bookmark 出发，
-- 这些行永远不会被读到
DELETE FROM site_asset WHERE owner_type IS NULL OR owner_id IS NULL;

UPDATE site_display_pref p
   SET site_id = b.site_id
  FROM bookmark b
 WHERE b.id = p.bookmark_id
   AND p.site_id IS NULL;
DELETE FROM site_display_pref WHERE site_id IS NULL;
UPDATE site_display_pref SET id = gen_random_uuid()::text WHERE id IS NULL;

-- 过渡期新插入的行可能与既有行重复（旧代码按 bookmark 存，新键按 site 存），
-- 收紧之前再去重一次；保留规则与前半段一致
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

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. 收紧
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE site_asset
    ALTER COLUMN owner_type SET NOT NULL,
    ALTER COLUMN owner_id   SET NOT NULL;

-- 主键切换已在前半段完成（那一步必须早于 `bookmark_id DROP NOT NULL`，
-- 因为 PostgreSQL 不允许对主键列去掉 NOT NULL），这里只剩 site_id 要收紧。
ALTER TABLE site_display_pref ALTER COLUMN site_id SET NOT NULL;

-- bookmark.site_id 同理：旧代码 INSERT bookmark 不带它，所以也留到这一步才收紧
UPDATE bookmark b SET site_id = s.id FROM site s WHERE s.host = b.url_host AND b.site_id IS NULL;
-- 仍然挂不上站点的书签（host 异常等）先留着不动，只报出来给人看；直接 NOT NULL 会失败
DO $$
DECLARE orphans bigint;
BEGIN
    SELECT count(*) INTO orphans FROM bookmark WHERE site_id IS NULL;
    IF orphans > 0 THEN
        RAISE EXCEPTION '还有 % 条 bookmark 没有 site_id，先排查再收紧（SELECT id, url_host FROM bookmark WHERE site_id IS NULL）', orphans;
    END IF;
END $$;
ALTER TABLE bookmark ALTER COLUMN site_id SET NOT NULL;

COMMIT;

-- 验证：
-- SELECT count(*) FROM site_asset WHERE owner_type IS NULL OR owner_id IS NULL;          -- 应为 0
-- SELECT count(*) FROM site_display_pref WHERE site_id IS NULL OR id IS NULL;            -- 应为 0
-- SELECT count(*) FROM bookmark WHERE site_id IS NULL;                                   -- 应为 0
-- SELECT conname, contype FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
--  WHERE t.relname = 'site_display_pref';
