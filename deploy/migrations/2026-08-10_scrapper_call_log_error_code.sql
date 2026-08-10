-- 2026-08-10 — scrapper_call_log 补 error_code / target_status 两列，并补上这张表的第一个索引
--
-- 应用时机：**部署 API 之前**。写入包在 runCatching 里，列不存在只会打一行 warn，抓取本身照常跑
-- —— 代价是整段窗口内**每一条**调用日志都写不进去（整行 insert 失败，不是缺两列），而这张表是
-- 「哪些站点在反复失败、每次烧掉多少秒」唯一的数据来源。
--
-- 为什么要 error_code：现在只有自由文本 error_msg（形如 `FETCH_FAILED :: 412 ...`）。要按站点聚合
-- 出「主要败因」，靠 LIKE 从这段文本里猜错误码既不稳也没法建索引，而这个码本来就已经被
-- ApiServiceImpl 解析出来用于 classifyScrapperError 了，只是没有存下来。
--
-- 为什么要 target_status：已有的 http_status 记的是 **scrapper 自己**回的状态码（FETCH_FAILED 恒为
-- 502、TIMEOUT 恒为 504），跟目标站点无关。而「412 反爬」和「DNS 解析不了」要完全相反的处置，
-- 区分它们的恰恰是目标站点的最终状态码，它在错误响应的 fetch.httpStatus 里，此前被丢掉了。
--
-- 两列都可空、不给默认值：历史行确实没有这个信息，而 target_status 即便在新行里也常为空
-- （连接超时、DNS 失败这类根本没有状态码可言）。0 会被读成一个真实的状态码。
--
-- **不加索引**：排行页按时间窗聚合，现成的 idx_scrapper_call_log_create_time 就是它要的范围扫描
-- （btree 升序索引反向扫同样有效，不必为倒序再建一个）。至于「只聚合失败行」的部分索引，聚合本身
-- 要同时数总调用数和失败数，覆盖不到，建了也用不上。这个页面存在的目的就是先把量测出来，
-- 在没有一条慢查询记录之前先按猜想加索引，正是它要反对的做法。
--
-- 幂等，可重复执行。

ALTER TABLE public.scrapper_call_log ADD COLUMN IF NOT EXISTS error_code character varying(64);
COMMENT ON COLUMN public.scrapper_call_log.error_code IS
    'scrapper 返回的机器可读错误码（FETCH_FAILED / HEADLESS_FAILED / TIMEOUT / RECENTLY_FAILED /'
    ' HEADLESS_UNAVAILABLE / FORBIDDEN_TARGET / INVALID_URL / OSS_FAILED …），成功时为空。'
    ' 另有两个**我方自造**的值，用于标记压根没拿到 scrapper 回答的情形，它们不是契约的一部分：'
    ' SCRAPPER_UNREACHABLE（连不上抓取服务）、CONTRACT_MISMATCH（响应解析不了，两侧代码不同步）。null=历史行或成功行';

ALTER TABLE public.scrapper_call_log ADD COLUMN IF NOT EXISTS target_status integer;
COMMENT ON COLUMN public.scrapper_call_log.target_status IS
    '**目标站点**的最终 HTTP 状态码，取自 scrapper 错误响应的 fetch.httpStatus。'
    ' 与 http_status 不是一回事：那一列是 scrapper 自己回给我们的码（FETCH_FAILED 恒 502、TIMEOUT 恒 504）。'
    ' 「403/406/412 反爬」与「连不上/DNS 解析失败」的处置完全相反，而只有这一列分得开。'
    ' 没连上目标站点时本就没有状态码，为空是事实；成功时也为空（成功走 fetch.httpStatus 但不入本列，那恒为 2xx）';
