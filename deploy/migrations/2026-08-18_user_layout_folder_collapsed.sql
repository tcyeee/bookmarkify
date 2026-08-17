-- 2026-08-18 · 用户首页文件夹折叠状态
-- 折叠状态属于 user_layout_node；旧数据默认为展开。
-- 请先应用这条迁移，再部署读取该字段的新 API。
-- 幂等，可重复执行。

ALTER TABLE public.user_layout_node
    ADD COLUMN IF NOT EXISTS collapsed boolean DEFAULT false NOT NULL;
