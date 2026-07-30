-- 字段级人工锁定：内容定期重抓开启后，防止管理员手工改过的标题/简介/简称被静默覆盖。
--
-- 此前唯一的保护是 verify_flag，但它太粗——那是「整条记录人工确认、彻底停止抓取」的开关，
-- 拿它来保住一个标题会连图标的刷新一起停掉。
--
-- 取值是 BookmarkLockedField 枚举名的逗号拼接，如 'TITLE,APP_NAME'；NULL 表示没有锁。
-- 语义：手工编辑该字段 → 加锁；后台「一键更新」/「应用重新获取」显式采用抓取值 → 解锁。
--
-- 必须在部署新版 bookmarkify-api 之前执行（先 dev 库验证，再生产库）。

BEGIN;

ALTER TABLE bookmark ADD COLUMN IF NOT EXISTS locked_fields varchar(200);

COMMENT ON COLUMN bookmark.locked_fields IS '管理员手工锁定、不允许自动抓取覆盖的字段(BookmarkLockedField 枚举名逗号拼接)';

-- 历史数据不回填：谁的标题是手工改的、谁是抓来的，库里没有记录，猜不出来。
-- 已经人工确认过的书签本来就有 verify_flag 兜着（parseBookmark 对它们直接短路跳过抓取），
-- 其余记录从下一次手工编辑开始享有锁保护。

COMMIT;

-- 验证：
-- SELECT locked_fields, count(*) FROM bookmark GROUP BY locked_fields;
