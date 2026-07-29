-- 删除 bookmark_logo：读写两端均已切换到 site_asset / site_display_pref。
--
-- 必须在 2026-07-29_site_assets_and_scrape_snapshot.sql 之后执行，且执行前请确认
-- API 已部署到切换后的版本（旧版本读这张表，删了会 500）。
--
-- 这张表的问题在于把三种生命周期完全不同的东西塞进了一行：
--   1. 抓取事实（iconBase64 / logoUrl）          —— 每次重抓都该覆盖
--   2. 文件元数据（size / width / height / suffix）—— 跟着资产走
--   3. 人工偏好（iconPadding / iconBgColor / useHdLogo）—— 重抓绝不能覆盖
-- 混在一起导致每次重抓都要做小心翼翼的部分更新。新模型里 1、2 归 site_asset，
-- 3 归 site_display_pref，且后者还按展示模式分了行。
--
-- 数据不做迁移：新模型需要的 contentHash / width / height / extractor 等字段旧表根本
-- 没有，迁过来也是残缺的。正确做法是部署后触发一次全量重抓，由 scrapper 按新契约重新
-- 产出。当前无真实用户，重抓成本可接受。

BEGIN;

DROP TABLE IF EXISTS bookmark_logo;

COMMIT;
