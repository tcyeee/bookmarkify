-- 全系统 OSS 对象的唯一账本。
--
-- 背景与设计决策见仓库根目录 FILE-SYSTEM-REFACTOR.md，这里只记与 DDL 直接相关的几条：
--
-- 1. 本阶段(P1)这张表是**旁路账本**：写入路径双写，读路径一行不改。因此应用它不会改变任何
--    线上行为，也不需要与 API 发布严格同步 —— 表缺失只会让双写的 runCatching 记 warn。
-- 2. 主键是 UUID 而不是 content_hash。去重能力来自下面的唯一索引，与主键选谁无关；用 hash 做
--    主键会让引用方与"内容寻址"这个决策强耦合，将来换哈希算法就得重写全部引用方。
-- 3. 没有 owner 列。去重意味着"同一份字节只有一行"，属主/原始文件名/上传时间这些**每次引用
--    各不相同**的信息必须下沉到引用方(P3 的 user_file_ref)，塞在这里必然填不出唯一的值。
--
-- 幂等，可重复执行。

BEGIN;

CREATE TABLE IF NOT EXISTS oss_object (
    id           varchar(40)  PRIMARY KEY,
    -- 完整 object key，不含域名、不含 query。域名与签名是 API 的部署策略，不进库。
    object_key   varchar(512) NOT NULL,
    -- 图片字节的 sha256，形如 sha256:<hex>。存量对象从未被哈希过，回填(P2)前为空。
    content_hash varchar(80),
    -- key 是怎么算出来的，决定这个对象能不能参与去重：
    --   CONTENT    sha256(字节)，可去重、对象不可变
    --   SOURCE_URL sha256(源URL)，自我覆盖、存储量有上界 —— 截图刻意长期保留这种，它每次抓都
    --              不一样，改成内容寻址等于无上界增长，而去重收益为零
    --   RANDOM     随机 UUID，用户上传走这条
    --   LEGACY     改造前写入、形态不明的存量对象
    addressing   varchar(16)  NOT NULL DEFAULT 'LEGACY',
    -- 谁写进桶的：USER_UPLOAD / SCRAPPER / SYSTEM
    source       varchar(32)  NOT NULL,
    size         bigint,
    mime         varchar(128),
    width        integer,
    height       integer,
    is_vector    boolean      NOT NULL DEFAULT false,
    environment  varchar(16)  NOT NULL DEFAULT 'PROD',
    create_time  timestamp    NOT NULL DEFAULT now(),
    -- 对账任务写：本次 ListObjects 在桶里确认到它还在
    last_seen_at timestamp,
    -- 对账任务写：本次扫描引用方表时还有人引用它
    last_ref_at  timestamp,
    -- ACTIVE / ORPHAN / DELETED。P2 只标记不删除。
    state        varchar(16)  NOT NULL DEFAULT 'ACTIVE'
);

-- 账本按 key 唯一。写入一律 upsert-by-key：同一个 key 必须永远拿到同一个 id，
-- 否则 SiteAssetWriter.isIdenticalToExisting 那个"站点没改版就跳过整体替换"的优化会静默失效，
-- 每次重抓都白删白插，且 fetched_at 被刷新、"这张图什么时候第一次见到"永久丢失。
CREATE UNIQUE INDEX IF NOT EXISTS idx_oss_object_key ON oss_object (object_key);

-- 查同一份字节落在哪些 key 上 —— P5 的去重就是靠这个索引找出重复对象的。
--
-- **这里刻意不是 UNIQUE。** 当前 key 还是 sha256(源URL)，"同一张图挂在多个 URL 下"正是常态
-- （这也正是要做去重的原因），此刻加唯一约束会把大量合法数据挡在门外。只有等 P5 把 key 改成
-- 内容寻址、"同 hash ⇒ 同 key"才真正成立，那时再收紧成 UNIQUE。
-- partial: 存量对象从未被哈希过，content_hash 为空的行不占索引。
CREATE INDEX IF NOT EXISTS idx_oss_object_hash
    ON oss_object (content_hash) WHERE content_hash IS NOT NULL;

-- 对账任务的扫描入口：挑出"还活着但很久没人引用"的候选
CREATE INDEX IF NOT EXISTS idx_oss_object_state ON oss_object (state, last_ref_at);

COMMIT;

-- 验证：
-- SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'oss_object' ORDER BY ordinal_position;
-- SELECT indexname FROM pg_indexes WHERE tablename = 'oss_object';
