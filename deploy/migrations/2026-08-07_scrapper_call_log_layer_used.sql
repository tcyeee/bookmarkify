-- scrapper 调用日志记录实际使用的抓取层（Layer 1 普通 HTTP / Layer 2 无头浏览器 / 站点官方 API）。
--
-- ⚠️ **必须在部署新 API 之前执行**：新代码每次调用 scrapper 都会往这一列写值，
-- 列不存在则整条日志 insert 失败。写入包在 runCatching 里，业务不受影响，但日志会整段消失
-- —— 而这张表存在的意义就是事后排障，静默丢失比报错更糟。
--
-- 契约里这个事实一直有（ScrapeResponse.fetch.layerUsed，请求 render.mode=AUTO 时由 scrapper
-- 回报到底走了哪层），只是没落库。后台"来源"列记的是元数据出处（og/json_ld/...），
-- 和"用什么手段把页面弄回来的"是两件事：SITE_API 救回来的页面 source 可能仍是 html。
--
-- 取值：HTTP / HEADLESS / SITE_API / UNKNOWN；抓取失败时为空（失败响应里的 fetch.layerUsed
-- 目前恒为 HTTP，宁可留空也不记一个不可信的值）。
--
-- 幂等：IF NOT EXISTS。

ALTER TABLE public.scrapper_call_log
    ADD COLUMN IF NOT EXISTS layer_used character varying(20);

COMMENT ON COLUMN public.scrapper_call_log.layer_used IS
    '实际抓取层：HTTP=Layer1 普通HTTP, HEADLESS=Layer2 无头浏览器, SITE_API=站点官方API 救援';
