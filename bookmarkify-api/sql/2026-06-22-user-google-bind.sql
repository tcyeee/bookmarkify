-- Google 账号关联：sys_user 表新增 google_id / google_email 字段
-- 日期: 2026-06-22
-- schema: bookmarkify
-- 说明: google_id 存 Google ID Token 的 sub claim(稳定唯一标识)，google_email 仅用于界面展示。
--      google_id 建唯一索引保证一个 Google 账号只能关联一个用户；可重复执行(IF NOT EXISTS)。

ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS google_id    varchar(100),
    ADD COLUMN IF NOT EXISTS google_email varchar(200);

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_google_id
    ON sys_user (google_id)
    WHERE google_id IS NOT NULL;
