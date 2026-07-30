-- 把巡检的调度状态从 bookmark.update_time 里拆出来。
--
-- 背景：update_time 一列同时承担三种语义——「内容最近修改时间」、「上次活性探测时间」、
-- 以及「防止被重复选中的调度游标」。后果是：管理员改一次标题就把这条记录的下次巡检推迟一整个
-- 检测周期；反过来 ping 成功只是 bump 时间戳，让这个对外字段不再可信；而「连续失败几次」
-- 「下次该什么时候查」这类调度状态压根没地方存。
--
-- 拆完之后 update_time 回归「记录最近修改时间」，巡检只看 next_check_at 一列。
--
-- 必须在部署新版 bookmarkify-api 之前执行（先 dev 库验证，再生产库）：新代码读这四列。

BEGIN;

ALTER TABLE bookmark
    ADD COLUMN IF NOT EXISTS last_parse_at    timestamp,
    ADD COLUMN IF NOT EXISTS last_check_at    timestamp,
    ADD COLUMN IF NOT EXISTS next_check_at    timestamp,
    ADD COLUMN IF NOT EXISTS consecutive_fail smallint NOT NULL DEFAULT 0;

COMMENT ON COLUMN bookmark.last_parse_at    IS '上次成功抓到内容的时间(内容陈旧度以此为准)';
COMMENT ON COLUMN bookmark.last_check_at    IS '上次活性探测的时间(不论结论)';
COMMENT ON COLUMN bookmark.next_check_at    IS '调度游标：定时巡检只按这一列选取候选';
COMMENT ON COLUMN bookmark.consecutive_fail IS '连续探测失败次数，驱动指数退避与归档';

-- 回填。next_check_at 刻意**不**直接取 update_time，而是加上对应的检测间隔：
-- 否则升级后第一轮巡检会把全表都当成「已到期」，无视配置的检测频率把所有书签重查一遍。
-- 间隔取管理员实际配置的值（system_config 里那条 JSON），没配过则用与代码一致的默认值。
WITH cfg AS (
    SELECT COALESCE((config_value::jsonb ->> 'activeCheckIntervalHours')::int, 168)  AS active_hours,
           COALESCE((config_value::jsonb ->> 'abnormalCheckIntervalHours')::int, 24) AS abnormal_hours
      FROM system_config
     WHERE config_key = 'bookmark_liveness_check_frequency'
     UNION ALL
    SELECT 168, 24
     WHERE NOT EXISTS (SELECT 1 FROM system_config WHERE config_key = 'bookmark_liveness_check_frequency')
     LIMIT 1
)
UPDATE bookmark b
   SET last_parse_at = b.update_time,
       last_check_at = b.update_time,
       next_check_at = COALESCE(b.update_time, b.create_time)
                       + make_interval(hours => CASE b.parse_status
                                                    WHEN 'UNREACHABLE' THEN cfg.abnormal_hours
                                                    ELSE cfg.active_hours
                                                END)
  FROM cfg
 WHERE b.next_check_at IS NULL;

-- 巡检的候选查询：WHERE parse_status <> 'PENDING' AND next_check_at <= now() ORDER BY next_check_at。
-- 部分索引而不是全列索引：PENDING 走的是另一套 30 分钟窗口的兜底对账，不该占这个索引的体积。
CREATE INDEX IF NOT EXISTS idx_bookmark_next_check
    ON bookmark (next_check_at)
    WHERE parse_status <> 'PENDING';

-- checkAll() 的谓词是 COALESCE(update_time, create_time)，普通列索引用不上，需要表达式索引。
CREATE INDEX IF NOT EXISTS idx_bookmark_pending_stale
    ON bookmark ((COALESCE(update_time, create_time)))
    WHERE parse_status = 'PENDING';

COMMIT;

-- 验证：
-- SELECT parse_status, count(*), min(next_check_at), max(next_check_at) FROM bookmark GROUP BY parse_status;
-- EXPLAIN SELECT * FROM bookmark WHERE parse_status <> 'PENDING' AND next_check_at <= now() ORDER BY next_check_at LIMIT 200;
