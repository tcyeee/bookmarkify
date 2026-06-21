-- 书签图标背景色：bookmark 表新增 icon_bg_color 字段
-- 日期: 2026-06-22
-- schema: bookmarkify
-- 说明: 后台「书签图标管理」支持自定义图标背景色；可重复执行（IF NOT EXISTS）。

ALTER TABLE bookmarkify.bookmark
    ADD COLUMN IF NOT EXISTS icon_bg_color varchar(32);
