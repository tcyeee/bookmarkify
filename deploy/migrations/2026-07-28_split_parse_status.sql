-- 拆分 bookmark.parse_status 枚举：LOADING/SUCCESS/CLOSED/BLOCKED -> PENDING/SUCCESS/UNREACHABLE
-- 新增 bookmark.anti_crawler_blocked 承载原 BLOCKED 里"抓到内容但疑似反爬"的语义。
--
-- 必须在部署新版 bookmarkify-api 代码之前执行（先 dev 库验证，再生产库）：
-- MyBatis-Plus 默认 EnumTypeHandler 按枚举 .name() 存字符串，新代码的 ParseStatusEnum.valueOf()
-- 不认识旧的 LOADING/CLOSED/BLOCKED 字符串，若代码先于数据变更上线会导致读取旧行时反序列化失败。
--
-- 执行前建议先确认: SELECT parse_status, count(*) FROM bookmark GROUP BY parse_status;

BEGIN;

-- 1) 新增列，默认 false，不影响现有行为
ALTER TABLE bookmark ADD COLUMN IF NOT EXISTS anti_crawler_blocked boolean NOT NULL DEFAULT false;

-- 2) 回填 anti_crawler_blocked：仅 is_activity=true 的 BLOCKED 行代表
--    "抓到内容但 detectAntiCrawler() 判定疑似反爬"（BookmarkEntity.successInit 路径）；
--    is_activity=false 的 BLOCKED 行是 parseLocally() 里 403 抓取异常，语义上是"没抓到内容"，
--    应归入 UNREACHABLE，而不是反爬标记。必须在下一步改写 parse_status 之前执行。
UPDATE bookmark SET anti_crawler_blocked = true WHERE parse_status = 'BLOCKED' AND is_activity = true;

-- 3) 枚举值改名/合并
UPDATE bookmark SET parse_status = 'PENDING'     WHERE parse_status = 'LOADING';
UPDATE bookmark SET parse_status = 'UNREACHABLE' WHERE parse_status = 'CLOSED';
UPDATE bookmark SET parse_status = 'SUCCESS'     WHERE parse_status = 'BLOCKED' AND is_activity = true;
UPDATE bookmark SET parse_status = 'UNREACHABLE' WHERE parse_status = 'BLOCKED' AND is_activity = false;

COMMIT;

-- 验证：应只剩三种值
-- SELECT parse_status, count(*) FROM bookmark GROUP BY parse_status;
