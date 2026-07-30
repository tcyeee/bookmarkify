-- 活性探测结论三态化：ALIVE / DEAD / UNKNOWN。
--
-- 背景：pingWebsite 此前把「抓取服务不可达 / 鉴权失败 / 被限流 503 / 契约不符」全部塌缩成
-- alive=false，巡检任务据此把书签写成 UNREACHABLE。一次我方故障就能按每小时数百条的速度
-- 把健康书签洗成失效。新增 outcome 列承载真实结论，alive 降级为它的派生视图。
--
-- 必须在部署新版 bookmarkify-api 之前执行（先 dev 库验证，再生产库）：
-- 新代码读 outcome 列，旧库没有这一列会直接查询失败。
--
-- 本文件同时补齐 bookmark_ping_log 的建表语句——这张表此前只存在于生产库里，
-- 仓库中从未有过它的 DDL，新环境照文档搭不起来。已存在时 CREATE TABLE IF NOT EXISTS 是空操作，
-- 列类型以生产库现状为准，不做对齐。

BEGIN;

-- 1) 补齐建表语句（老环境上是空操作）
CREATE TABLE IF NOT EXISTS bookmark_ping_log (
    id              varchar(40)  PRIMARY KEY,
    bookmark_id     varchar(40)  NOT NULL,
    url_host        varchar(200) NOT NULL,
    alive           boolean,
    triggered_parse boolean      NOT NULL DEFAULT false,
    create_time     timestamp    NOT NULL DEFAULT now()
);

-- 2) 新增结论列
ALTER TABLE bookmark_ping_log ADD COLUMN IF NOT EXISTS outcome varchar(16);

-- 3) 回填历史行：老数据只有二值，一律按字面意义翻译。
--    这批 DEAD 里混着「其实是我方故障」的行，但事后已无从分辨，不做猜测。
UPDATE bookmark_ping_log
   SET outcome = CASE WHEN alive THEN 'ALIVE' ELSE 'DEAD' END
 WHERE outcome IS NULL;

ALTER TABLE bookmark_ping_log ALTER COLUMN outcome SET NOT NULL;

-- 4) alive 允许为 NULL：UNKNOWN 的行既不是存活也不是失活。
--    历史行不受影响；新行由应用侧按 outcome 派生。
ALTER TABLE bookmark_ping_log ALTER COLUMN alive DROP NOT NULL;

-- 5) 索引。这张表此前一个索引都没有，而它是只增不减的日志表：
--    后台列表按 create_time DESC 分页（全表排序），每日清理任务按 create_time 删除，
--    排查单个书签时按 bookmark_id 过滤。
CREATE INDEX IF NOT EXISTS idx_ping_log_create_time ON bookmark_ping_log (create_time DESC);
CREATE INDEX IF NOT EXISTS idx_ping_log_bookmark ON bookmark_ping_log (bookmark_id, create_time DESC);

COMMIT;

-- 验证：
-- SELECT outcome, count(*) FROM bookmark_ping_log GROUP BY outcome;
