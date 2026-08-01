-- 把 content_hash 上的索引收紧成 UNIQUE，正式启用存储层去重。
--
-- **不要和前面几个迁移一起执行。** 这一条有一个无法在 SQL 里表达的前置条件：
--
--   scrapper 的内容寻址版本已上线，且**全量重抓已经完成**。
--
-- 原因：唯一约束成立的前提是"同 hash ⇒ 同 key"。只有当 key 由 sha256(字节) 得出时这才为真。
-- 存量对象的 key 还是 sha256(源URL)，同一张图挂在多个 URL 下就是多个 key、同一个 hash ——
-- 在重抓完成前执行这一句，CREATE UNIQUE INDEX 会直接失败（好过悄悄放行）。
--
-- 幂等，可重复执行。

BEGIN;

-- 前置检查：还有重复 hash 就直接报错退出，而不是留下一个半成品索引。
-- 返回的行数就是还没被重抓统一到内容寻址的对象数。
DO $$
DECLARE dup_count integer;
BEGIN
    SELECT count(*) INTO dup_count FROM (
        SELECT content_hash
        FROM oss_object
        WHERE content_hash IS NOT NULL AND state <> 'DELETED'
        GROUP BY content_hash
        HAVING count(*) > 1
    ) t;

    IF dup_count > 0 THEN
        RAISE EXCEPTION
            '仍有 % 个 content_hash 对应多个 object_key，说明全量重抓尚未完成；'
            '本迁移必须在内容寻址生效且重抓完毕之后执行。'
            '排查：SELECT content_hash, count(*), array_agg(object_key) FROM oss_object '
            'WHERE content_hash IS NOT NULL AND state <> ''DELETED'' '
            'GROUP BY content_hash HAVING count(*) > 1;', dup_count;
    END IF;
END $$;

DROP INDEX IF EXISTS idx_oss_object_hash;

-- 排除 DELETED：那些行只作审计留存，对象已经不在桶里，不该继续占用唯一性名额
CREATE UNIQUE INDEX IF NOT EXISTS idx_oss_object_hash
    ON oss_object (content_hash)
    WHERE content_hash IS NOT NULL AND state <> 'DELETED';

COMMIT;

-- 验证：
-- SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_oss_object_hash';
