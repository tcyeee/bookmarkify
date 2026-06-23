-- bookmark 表新增 use_hd_logo：是否在前台用高清 LOGO 渲染
-- 日期: 2026-06-23
-- schema: bookmarkify
-- 说明: 控制 web 前台渲染时用高清 LOGO 还是小图标。回填保持现状(尺寸达标即自动用高清)，
--      避免存量书签退化为小图标。可重复执行。
ALTER TABLE bookmark
    ADD COLUMN IF NOT EXISTS use_hd_logo boolean NOT NULL DEFAULT false;

UPDATE bookmark SET use_hd_logo = true WHERE maximal_logo_size > 50;
