-- 2026-08-18 · 用户首页文件夹颜色
-- 颜色属于 user_layout_node（文件夹节点），可空表示使用前端默认颜色。
-- 请先应用这条迁移，再部署读取该字段的新 API。
-- 幂等，可重复执行。

ALTER TABLE public.user_layout_node
    ADD COLUMN IF NOT EXISTS color character varying(7);
