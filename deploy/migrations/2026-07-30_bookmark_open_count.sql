-- 记录用户每个书签的打开次数（仅做记录，暂无展示需求）。

BEGIN;

ALTER TABLE bookmark_user_link
    ADD COLUMN IF NOT EXISTS open_count integer NOT NULL DEFAULT 0;

COMMENT ON COLUMN bookmark_user_link.open_count IS '用户打开该书签的累计次数';

COMMIT;
