-- 一次性收尾：修正说谎的 addressing、收紧回填完成的列、清掉重复索引、补引用列索引。
--
-- 来源是一次「库里实际表结构 ↔ 代码实体类」的全量比对。好消息先说：25 个实体 × 25 张表
-- 的**列级结构完全对齐**，没有一处"实体有字段库里没列"，也没有一处死列。下面这些全部是
-- 约束/默认值/索引层面的残留 —— 多轮迁移各建各的、回填完没收紧、改名只改了一半。
--
-- 幂等，可重复执行。**不含任何 DROP TABLE / DELETE**。
--
-- 唯一需要人工判断的一项（重复 content_hash）单独放在文件末尾，只有诊断查询、不执行修改。

BEGIN;

-- ── 1. parse_status 的默认值仍是 'LOADING' ──────────────────────────────────
-- ParseStatusEnum 里根本没有 LOADING 这个常量（只有 PENDING/SUCCESS/UNREACHABLE/ARCHIVED），
-- 这是更早一轮把 LOADING 改名成 PENDING 时漏掉的默认值 —— 同一次改名里，
-- idx_bookmark_pending 的谓词已经改成 'PENDING' 了，只有默认值留在原地。
--
-- 现在没炸是因为实体给了 Kotlin 默认值、insert 永远显式带这一列，DB 默认值走不到。
-- 但任何绕过实体的原生 SQL（数据修复、INSERT ... SELECT、管理端脚本）只要不写这一列，
-- 就会落进一行 'LOADING'，此后**任何读到它的查询都会抛
-- IllegalArgumentException: No enum constant ParseStatusEnum.LOADING** ——
-- 炸的是整个书签列表接口，不是单行降级。
ALTER TABLE bookmark ALTER COLUMN parse_status SET DEFAULT 'PENDING';

-- ── 2. varchar 长度不足 ─────────────────────────────────────────────────────
-- nsfw_reason 存的是 DeepSeek 生成的自然语言判定理由，varchar(50) 极易超出，
-- 一超就是 PSQLException: value too long，整条 NSFW 判定写入失败。
--
-- 实体侧原本标的是 @field:Max(50)，而 @Max 只支持数值类型、对 String 完全无效
-- （严格说会抛 UnexpectedTypeException），等于没有任何截断保护。那 29 处标注已在
-- 同批代码里改成 @field:Size，但注解只是校验、不会截断，列宽仍需放开。
ALTER TABLE bookmark ALTER COLUMN nsfw_reason TYPE varchar(500);
ALTER TABLE site     ALTER COLUMN nsfw_reason TYPE varchar(500);

-- scrapper_call_log.url 是 varchar(500)，而同样存 URL 的 bookmark_user_link.url_full 和
-- scrape_snapshot.url 都是 varchar(1000)。记一条长 URL 时日志写入抛异常，
-- 反过来把被记录的业务调用一起带崩 —— 日志表不该有这种能力。
ALTER TABLE scrapper_call_log ALTER COLUMN url TYPE varchar(1000);

-- ── 3. 回填已完成、代码侧非空的列收紧 NOT NULL ───────────────────────────────
-- 这些列都是 site-layering / site_asset 改造时新加的，为兼容存量建成 nullable，
-- 回填完之后没有收紧。对应的 Kotlin 字段全部是非空类型：MyBatis-Plus 走无参构造 +
-- 反射赋值，塞一个 null 进非空 String 会当场 NPE 或让非法对象逃逸。
--
-- 执行前这几列的实际 NULL 行数均已核对为 0；若因新数据导致下面任一句失败，
-- 说明确实有写入路径在产生 NULL，那是代码要修的 bug，不要靠放宽约束绕过。
ALTER TABLE bookmark           ALTER COLUMN site_id    SET NOT NULL;
ALTER TABLE site_asset         ALTER COLUMN owner_type SET NOT NULL;
ALTER TABLE site_asset         ALTER COLUMN owner_id   SET NOT NULL;
ALTER TABLE site_display_pref  ALTER COLUMN site_id    SET NOT NULL;
ALTER TABLE user_info          ALTER COLUMN nick_name  SET NOT NULL;
ALTER TABLE user_info          ALTER COLUMN device_id  SET NOT NULL;
ALTER TABLE bookmark_user_link ALTER COLUMN url_full   SET NOT NULL;

-- site_asset.file_id **刻意保持可空**，不要顺手一起收紧：大量资产根本没落对象存储
-- （抓取只做了 PROBE、或下载失败）。库里现有 2 行就是 163/豆瓣的 favicon 被 403 挡掉，
-- file_id 与 storage_url 双双为 NULL —— 这是正确的降级形态，不是待回填的脏数据。

-- ── 4. 完全重复的索引 ───────────────────────────────────────────────────────
-- 多轮迁移各建各的、互相不知道对方存在。每一组的两个索引定义**逐字相同**，
-- 留着只是让每次写入多维护一棵 B-tree。
DROP INDEX IF EXISTS idx_bookmark_pending_stale;      -- 与 idx_bookmark_pending 完全相同
DROP INDEX IF EXISTS idx_admin_grid_config_owner;     -- 与 uq_admin_grid_config_admin_grid 完全相同
DROP INDEX IF EXISTS idx_system_config_key;           -- 与约束自带的 system_config_config_key_key 完全相同
DROP INDEX IF EXISTS idx_ping_log_bookmark_id;        -- 被 idx_ping_log_bookmark(bookmark_id, create_time DESC) 完全包含

-- ── 5. 补两个引用列的索引 ───────────────────────────────────────────────────
-- OssReconcileServiceImpl.collectReferencedKeys 每轮对账都要扫全部引用方，问
-- "还有没有人指向这个对象"。site_asset.file_id 有 idx_site_asset_file，
-- 另外两个引用列**一个索引都没有**，只能全表扫。
--
-- 现在数据量小无所谓，但这正是对账任务将来第一个变慢的地方，而对账已经是全量装内存的。
CREATE INDEX IF NOT EXISTS idx_user_info_avatar_file
    ON user_info (avatar_file_id) WHERE avatar_file_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_background_image_file
    ON background_image (file_id);

-- ── 6. 修正说谎的 addressing ────────────────────────────────────────────────
-- SiteAssetWriter.registerObjects 原先按 role 推断寻址方式：非截图一律标 CONTENT，
-- 从不校验 key 是否真的是内容寻址。于是 scrapper 升级前写入的 `sha256(源URL).<ext>`
-- 形态的 key —— 与字节无关、重抓会被覆盖 —— 也被标成了 CONTENT。
--
-- 这不只是标签错误。OssObjectEntity.immutable 就是从这一列推出来的，OssUtils.signAsset
-- 据此签 24 小时长效链接。也就是说一批随时可能被覆盖的对象，正以"字节永不改变"的名义
-- 拿着长效 URL：站点换图后最长 24 小时都是旧图，而且重抓也修不好。
--
-- 代码侧已改为按 key 的实际形态判定（key 末段 == content_hash 的 hex 且无扩展名 ⇒ CONTENT，
-- 见 SiteAssetWriter.addressingOf）。但入账走的是 INSERT ... ON CONFLICT DO NOTHING，
-- 存量行不会被新代码纠正，只能在这里回填一次。
UPDATE oss_object
SET addressing = 'SOURCE_URL'
WHERE addressing = 'CONTENT'
  AND content_hash IS NOT NULL
  -- key 的末段不等于 hash 的 hex ⇒ 它压根不是由字节算出来的
  AND split_part(object_key, '/', array_length(string_to_array(object_key, '/'), 1))
      <> split_part(content_hash, ':', 2);

-- 没有 content_hash 就无从判定形态，如实降级成 LEGACY（"改造前写入、形态不明"），
-- 而不是继续冒充 CONTENT 去换那 24 小时的签名有效期。
UPDATE oss_object
SET addressing = 'LEGACY'
WHERE addressing = 'CONTENT'
  AND content_hash IS NULL;

COMMIT;

-- ═══════════════════════════════════════════════════════════════════════════
-- 以下**不执行**，只是把「重复 content_hash」这件事的诊断与正确解法记在案发现场。
-- ═══════════════════════════════════════════════════════════════════════════
--
-- 现象：2026-08-03_oss_object_hash_unique.sql 的前置检查会 RAISE EXCEPTION 中止，
-- 因为存在同一个 content_hash 对应多个 object_key 的行。
--
-- 诊断：
--   SELECT sa.owner_id, s.host, oo.create_time::date, oo.object_key
--   FROM oss_object oo
--   JOIN site_asset sa ON sa.file_id = oo.id
--   LEFT JOIN site s ON s.id = sa.owner_id
--   WHERE oo.content_hash IN (
--     SELECT content_hash FROM oss_object
--     WHERE content_hash IS NOT NULL AND state <> 'DELETED'
--     GROUP BY 1 HAVING count(*) > 1)
--   ORDER BY s.host, oo.create_time;
--
-- 上次跑出来的结果说明了根因 —— 不是"旧行忘了清"：
--
--   bilibili.com      2026-08-01  scrapper/asset/<sha256(源URL)>.ico   ← 升级前抓的
--   bilibili.com      2026-08-01  scrapper/asset/<sha256(源URL)>.png
--   www.bilibili.com  2026-08-02  scrapper/asset/<sha256(字节)>        ← 升级后抓的
--   www.bilibili.com  2026-08-02  scrapper/asset/<sha256(字节)>
--
-- `bilibili.com` 与 `www.bilibili.com` 是两个独立的 site 行，一个在 scrapper 升级前抓过、
-- 一个在升级后抓过，同样的字节落成了两种 key。
--
-- **不要用"把旧行标 DELETED"绕过前置检查。** 那 4 行每一行都还有 1 条 site_asset 指着，
-- 标掉即是 4 个悬空引用 + 4 张图变空白。唯一正确的路径：
--
--   1. 重抓持有旧 key 的站点（这里是 bilibili.com），让它的 site_asset 改指内容寻址的新 key
--   2. 确认旧 key 的引用归零：
--        SELECT oo.object_key, count(sa.id) FROM oss_object oo
--        LEFT JOIN site_asset sa ON sa.file_id = oo.id
--        WHERE oo.object_key LIKE '%.ico' OR oo.object_key LIKE '%.png'
--        GROUP BY 1 HAVING count(sa.id) = 0;
--   3. 让对账任务把它们判成 ORPHAN 并回收（或手工标 DELETED）
--   4. 此时才能执行 2026-08-03_oss_object_hash_unique.sql
--
-- 顺带记一笔（不在本次范围）：`bilibili.com` 和 `www.bilibili.com` 没有被归一成同一个 site。
-- www 变体各存一份站点资产，是 site-layering 那一层的事。

-- 验证：
-- SELECT column_default FROM information_schema.columns
--   WHERE table_name='bookmark' AND column_name='parse_status';           -- 期望 'PENDING'
-- SELECT addressing, count(*) FROM oss_object GROUP BY 1;                 -- CONTENT 应只剩真正内容寻址的
-- SELECT indexname FROM pg_indexes WHERE tablename IN ('bookmark','system_config','admin_grid_config','bookmark_ping_log');
-- SELECT count(*) FROM information_schema.columns
--   WHERE table_name='site_asset' AND column_name IN ('owner_type','owner_id') AND is_nullable='YES';  -- 期望 0
