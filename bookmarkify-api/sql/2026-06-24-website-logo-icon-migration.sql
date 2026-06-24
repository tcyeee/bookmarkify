-- 把 bookmark 表中「图标相关字段」迁移到 website_logo（与书签 1:1）
-- 日期: 2026-06-24
-- schema: bookmarkify
--
-- 背景：图标相关字段(小图标/高清LOGO/内边距/背景色/高清开关)原先在 bookmark 表，
--      现统一收拢到 website_logo（每书签一行）。涉及 bookmark 列删除，必须与新代码上线配合：
--
--   ┌── Part 1（向后兼容，旧代码无感）：给 website_logo 增列 + 从 bookmark 回填。
--   │     可在部署新代码【之前】执行。
--   ├── 部署新代码（push 到 prod）。新代码从 website_logo 读写图标字段。
--   └── Part 2（破坏性）：再回填一次 + 去重 + 唯一约束 + 删 is_og_img + 删 bookmark 旧列。
--         必须在新代码【上线之后】执行；若旧代码仍在跑时执行会导致生产报错。
--
-- 两部分均可重复执行（幂等）。


-- =====================================================================
-- Part 1：增列 + 回填（向后兼容，部署前执行）
-- =====================================================================

ALTER TABLE website_logo
    ADD COLUMN IF NOT EXISTS icon_base64   text,
    ADD COLUMN IF NOT EXISTS logo_url      varchar(500),
    ADD COLUMN IF NOT EXISTS icon_padding  integer NOT NULL DEFAULT 25,
    ADD COLUMN IF NOT EXISTS icon_bg_color varchar(32),
    ADD COLUMN IF NOT EXISTS use_hd_logo   boolean NOT NULL DEFAULT false;

-- 回填①：把现有 bookmark 的图标字段写入其对应 website_logo「主图标行」(每书签取 height 最大的一行)
WITH primary_logo AS (
    SELECT DISTINCT ON (bookmark_id) id, bookmark_id
    FROM website_logo
    ORDER BY bookmark_id, height DESC, create_time DESC
)
UPDATE website_logo wl
SET icon_base64   = b.icon_base64,
    logo_url      = b.logo_url,
    icon_padding  = b.icon_padding,
    icon_bg_color = b.icon_bg_color,
    use_hd_logo   = b.use_hd_logo
FROM primary_logo pl
         JOIN bookmark b ON b.id = pl.bookmark_id
WHERE wl.id = pl.id;

-- 回填②：对「有书签但完全没有 website_logo 行」的，补插一行（携带其图标字段）
INSERT INTO website_logo
(id, bookmark_id, icon_base64, logo_url, icon_padding, icon_bg_color, use_hd_logo,
 size, height, width, suffix, create_time, update_time)
SELECT gen_random_uuid()::text, b.id, b.icon_base64, b.logo_url, b.icon_padding, b.icon_bg_color, b.use_hd_logo,
       0, COALESCE(b.maximal_logo_size, 0), COALESCE(b.maximal_logo_size, 0), '', now(), now()
FROM bookmark b
WHERE NOT EXISTS (SELECT 1 FROM website_logo w WHERE w.bookmark_id = b.id);


-- =====================================================================
-- Part 2：再回填 + 去重 + 唯一约束 + 删旧列（务必在新代码上线之后执行）
-- =====================================================================

-- 2.1 再回填一次，捕获 Part1 之后、新代码上线之前旧代码可能产生的增量写入（与 Part1 相同，幂等）
WITH primary_logo AS (
    SELECT DISTINCT ON (bookmark_id) id, bookmark_id
    FROM website_logo
    ORDER BY bookmark_id, height DESC, create_time DESC
)
UPDATE website_logo wl
SET icon_base64   = b.icon_base64,
    logo_url      = b.logo_url,
    icon_padding  = b.icon_padding,
    icon_bg_color = b.icon_bg_color,
    use_hd_logo   = b.use_hd_logo
FROM primary_logo pl
         JOIN bookmark b ON b.id = pl.bookmark_id
WHERE wl.id = pl.id;

INSERT INTO website_logo
(id, bookmark_id, icon_base64, logo_url, icon_padding, icon_bg_color, use_hd_logo,
 size, height, width, suffix, create_time, update_time)
SELECT gen_random_uuid()::text, b.id, b.icon_base64, b.logo_url, b.icon_padding, b.icon_bg_color, b.use_hd_logo,
       0, COALESCE(b.maximal_logo_size, 0), COALESCE(b.maximal_logo_size, 0), '', now(), now()
FROM bookmark b
WHERE NOT EXISTS (SELECT 1 FROM website_logo w WHERE w.bookmark_id = b.id);

-- 2.2 去重：每个 bookmark_id 仅保留一行（有 icon_base64 优先，其次 height 最大）
DELETE FROM website_logo wl
USING (
    SELECT id,
           row_number() OVER (
               PARTITION BY bookmark_id
               ORDER BY (icon_base64 IS NOT NULL) DESC, height DESC, create_time DESC
               ) AS rn
    FROM website_logo
) d
WHERE wl.id = d.id AND d.rn > 1;

-- 2.3 唯一约束（保证 1:1）
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uk_website_logo_bookmark') THEN
        ALTER TABLE website_logo ADD CONSTRAINT uk_website_logo_bookmark UNIQUE (bookmark_id);
    END IF;
END $$;

-- 2.4 删除已无意义的 is_og_img（OG 图不再入库，恒为 false）
ALTER TABLE website_logo DROP COLUMN IF EXISTS is_og_img;

-- 2.5 从 bookmark 删除已迁出的图标列
ALTER TABLE bookmark
    DROP COLUMN IF EXISTS icon_base64,
    DROP COLUMN IF EXISTS logo_url,
    DROP COLUMN IF EXISTS maximal_logo_size,
    DROP COLUMN IF EXISTS icon_padding,
    DROP COLUMN IF EXISTS icon_bg_color,
    DROP COLUMN IF EXISTS use_hd_logo;
