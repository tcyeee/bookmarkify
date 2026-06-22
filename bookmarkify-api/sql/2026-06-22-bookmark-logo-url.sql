-- 高清 LOGO 地址：bookmark 表新增 logo_url 字段
-- 日期: 2026-06-22
-- schema: bookmarkify
-- 说明: 用户添加书签时 scrapper 解析出的高清 LOGO 上传 OSS 后，将其永久地址落库；可重复执行（IF NOT EXISTS）。

ALTER TABLE bookmarkify.bookmark
    ADD COLUMN IF NOT EXISTS logo_url varchar(500);
