-- 2026-08-03 三层正名：site / page / bookmark
--
-- ⚠️⚠️ **需要停机窗口。表重命名无法向后兼容** —— 旧代码撞上新表名是全量 500，
-- 新代码撞上旧表名同样。执行顺序必须是：停旧实例 → 跑本文件 → 起新实例。
-- 本项目是单实例部署（见 bookmarkify-api/CLAUDE.md），停机时间就是一次容器重启。
--
-- ## 改了什么
--
-- 表名此前用「前缀标明归属层」的规则，但有一半的表没遵守，最误导的一条是：
-- `bookmark` 表指的是**页面**，而用户嘴里的「书签」其实是 `bookmark_user_link`。
-- 于是 `bookmark_user_link` 读起来像「书签的书签链接」，也让人误以为 bookmark 和 site
-- 是同一个概念（实测 www.bilibili.com 一个 site 下挂着 4 个 bookmark：首页 + 3 个视频）。
--
--   site      一域名一行        （不变）
--   page      一个规范化页面一行 （原 bookmark）
--   bookmark  用户的一次收藏     （原 bookmark_user_link）← 用户说的"书签"
--
-- ## 为什么列名也要一起改
--
-- 不改的话，`page` 表会被一个叫 `bookmark_id` 的列引用 —— 表名和列名各说各话，
-- 比重命名前更糟。所以指向页面的 `bookmark_id` 一律改成 `page_id`。
--
-- ## 幂等性
--
-- 每一步都用 `IF EXISTS` 型的存在性判断包起来，重跑安全（重跑时全部跳过）。

BEGIN;

------------------------------------------------------------------------------
-- 1. 表重命名
------------------------------------------------------------------------------
-- ⚠️ 顺序不能反：`bookmark` 这个名字要先腾出来，再给 bookmark_user_link 用。
-- 反过来做的话第一步就会撞名，而且是在事务里报错回滚 —— 好过静默指向错表，
-- 但仍然要按这个顺序读才看得懂。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public' AND tablename='bookmark')
       AND NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public' AND tablename='page') THEN
        ALTER TABLE public.bookmark            RENAME TO page;
        ALTER TABLE public.bookmark_user_link  RENAME TO bookmark;
        ALTER TABLE public.bookmark_ping_log   RENAME TO page_ping_log;
        ALTER TABLE public.bookmark_category   RENAME TO page_category;
        ALTER TABLE public.bookmark_sweep_log  RENAME TO sweep_log;
        ALTER TABLE public.site_page_meta      RENAME TO page_meta;
    END IF;
END $$;

------------------------------------------------------------------------------
-- 2. 列重命名：指向页面的一律叫 page_id
------------------------------------------------------------------------------
DO $$
DECLARE t text;
BEGIN
    -- 这 7 张表的 bookmark_id 全部指向 page
    FOREACH t IN ARRAY ARRAY['bookmark','page_ping_log','page_category','page_meta',
                             'site_asset','site_display_pref','scrape_snapshot'] LOOP
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema='public' AND table_name=t AND column_name='bookmark_id') THEN
            EXECUTE format('ALTER TABLE public.%I RENAME COLUMN bookmark_id TO page_id', t);
        END IF;
    END LOOP;

    -- user_share_bookmark 指向的是「用户的收藏」，那现在就叫 bookmark
    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema='public' AND table_name='user_share_bookmark'
                 AND column_name='bookmark_user_link_id') THEN
        ALTER TABLE public.user_share_bookmark RENAME COLUMN bookmark_user_link_id TO bookmark_id;
    END IF;
END $$;

------------------------------------------------------------------------------
-- 3. 索引与约束改名
------------------------------------------------------------------------------
-- ALTER TABLE RENAME **不会**连带改索引名和约束名，不显式改的话会留下
-- 「表叫 page、主键约束叫 bookmark_pkey」这种正是本次要消灭的错位。
DO $$
DECLARE r record;
BEGIN
    FOR r IN SELECT * FROM (VALUES
        ('bookmark_pkey',                  'page_pkey'),
        ('uk_bookmark_canonical',          'uk_page_canonical'),
        ('idx_bookmark_due_check',         'idx_page_due_check'),
        ('idx_bookmark_pending',           'idx_page_pending'),
        ('idx_bookmark_site',              'idx_page_site'),
        ('bookmark_user_link_pkey',        'bookmark_pkey'),
        ('uk_bul_uid_bookmark',            'uk_bookmark_uid_page'),
        ('idx_bul_uid_live',               'idx_bookmark_uid_live'),
        ('idx_bul_layout_node',            'idx_bookmark_layout_node'),
        ('idx_bul_bookmark',               'idx_bookmark_page'),
        ('bookmark_ping_log_pkey',         'page_ping_log_pkey'),
        ('idx_ping_log_bookmark',          'idx_ping_log_page'),
        ('bookmark_category_pkey',         'page_category_pkey'),
        ('uk_bookmark_category',           'uk_page_category'),
        ('idx_bookmark_category_bookmark', 'idx_page_category_page'),
        ('bookmark_sweep_log_pkey',        'sweep_log_pkey'),
        ('site_page_meta_pkey',            'page_meta_pkey'),
        ('idx_scrape_snapshot_bookmark',   'idx_scrape_snapshot_page')
    ) AS v(old_name, new_name) LOOP
        IF EXISTS (SELECT 1 FROM pg_class WHERE relname = r.old_name AND relkind IN ('i','I'))
           AND NOT EXISTS (SELECT 1 FROM pg_class WHERE relname = r.new_name) THEN
            EXECUTE format('ALTER INDEX public.%I RENAME TO %I', r.old_name, r.new_name);
        END IF;
    END LOOP;
END $$;

------------------------------------------------------------------------------
-- 4. 复核
------------------------------------------------------------------------------
-- 三层表都在、且没有任何残留的 bookmark_id 指向 page。
DO $$
DECLARE missing text; stale int;
BEGIN
    SELECT string_agg(t, ', ') INTO missing FROM unnest(ARRAY['site','page','bookmark']) t
    WHERE NOT EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public' AND tablename=t);
    IF missing IS NOT NULL THEN
        RAISE EXCEPTION '三层表缺失: %', missing;
    END IF;

    SELECT count(*) INTO stale FROM information_schema.columns
    WHERE table_schema='public' AND column_name='bookmark_id'
      AND table_name IN ('page_ping_log','page_category','page_meta',
                         'site_asset','site_display_pref','scrape_snapshot');
    IF stale > 0 THEN
        RAISE EXCEPTION '仍有 % 张表的 bookmark_id 未改名为 page_id', stale;
    END IF;
END $$;

COMMIT;
