-- 把全部引用方从"存 object key / 存 user_file 行"改成统一存 oss_object.id。
--
-- 对应 FILE-SYSTEM-REFACTOR.md 的 P3 + P4。两阶段合并执行，因为 `user_file.origin_name`
-- 全项目只写不读，计划里那张 `user_file_ref` 引用表因此是多余的 ——
-- `background_image`(uid, file_id, create_time) 和 `user_info.avatar_file_id` 本身
-- 就是合格的引用方，各自一行对应一次引用，天然满足"去重后属主信息不能塞进内容表"的要求。
--
-- **必须在部署对应版本的 API 之前执行**：新代码读 oss_object 而不再读 user_file，
-- 先发代码会让所有头像和背景图变成空白。
--
-- 幂等，可重复执行。

BEGIN;

-- ── 1. site_asset 增加 file_id ──────────────────────────────────────────────
-- 可空是硬要求：大量资产根本没落对象存储（抓取时只做了 PROBE、或那张图指向外站直连），
-- 它们只有 resolved_url。文件表不是图片的唯一来源，展示层永远要处理多种形态。
ALTER TABLE site_asset ADD COLUMN IF NOT EXISTS file_id varchar(40);
CREATE INDEX IF NOT EXISTS idx_site_asset_file ON site_asset (file_id);

-- ── 2. user_file → oss_object ──────────────────────────────────────────────
-- **刻意沿用 user_file.id 作为 oss_object.id**：user_info.avatar_file_id 与
-- background_image.file_id 存的就是 user_file.id，id 不变则这两处引用一行都不用改，
-- 迁移的风险面从"重写两张业务表"缩到"插一批新行"。
--
-- 用户上传的 key 是随机 UUID，与字节和源地址都无关，故 addressing = RANDOM。
INSERT INTO oss_object (id, object_key, addressing, source, size, environment, create_time, state)
SELECT uf.id,
       CASE uf.type WHEN 'AVATAR' THEN 'bookmarkify/avatar' ELSE 'bookmarkify/bac' END
           || '/' || uf.current_name || '.' || uf.suffix,
       'RANDOM',
       'USER_UPLOAD',
       uf.size,
       COALESCE(uf.environment, 'PROD'),
       COALESCE(uf.create_time, now()),
       'ACTIVE'
FROM user_file uf
ON CONFLICT (object_key) DO NOTHING;

-- ── 3. 修正 P1 双写造成的 id 分叉 ────────────────────────────────────────────
-- P1 上线后新传的文件已经被双写进 oss_object，用的是**另一个**随机 id。对这些 key，
-- 上一步的 ON CONFLICT 会跳过插入，于是引用方仍指向 user_file.id —— 那个 id 在
-- oss_object 里并不存在。这两条 UPDATE 专门把它们改指到已存在的那一行。
-- 绝大多数行 oo.id = uf.id，条件直接过滤掉，不产生无谓写入。
UPDATE user_info ui
SET avatar_file_id = oo.id
FROM user_file uf
JOIN oss_object oo
  ON oo.object_key = (CASE uf.type WHEN 'AVATAR' THEN 'bookmarkify/avatar' ELSE 'bookmarkify/bac' END
                          || '/' || uf.current_name || '.' || uf.suffix)
WHERE ui.avatar_file_id = uf.id
  AND oo.id <> uf.id;

UPDATE background_image bi
SET file_id = oo.id
FROM user_file uf
JOIN oss_object oo
  ON oo.object_key = (CASE uf.type WHEN 'AVATAR' THEN 'bookmarkify/avatar' ELSE 'bookmarkify/bac' END
                          || '/' || uf.current_name || '.' || uf.suffix)
WHERE bi.file_id = uf.id
  AND oo.id <> uf.id;

-- ── 4. site_asset.file_id 回填 ──────────────────────────────────────────────
-- 只匹配裸 key。storage_url 里还有改造前写入的完整 URL，其中一部分指向的根本不是我方的桶
-- （资产只做了 PROBE 时存的是源站地址），它们保持 file_id = NULL，读路径走既有的兼容分支。
UPDATE site_asset sa
SET file_id = oo.id
FROM oss_object oo
WHERE sa.file_id IS NULL
  AND sa.storage_url IS NOT NULL
  AND sa.storage_url = oo.object_key;

COMMIT;

-- 验证：
-- 1) 引用方不应有悬空指针（两条都必须返回 0）
-- SELECT count(*) FROM user_info ui
--   WHERE ui.avatar_file_id IS NOT NULL
--     AND NOT EXISTS (SELECT 1 FROM oss_object oo WHERE oo.id = ui.avatar_file_id);
-- SELECT count(*) FROM background_image bi
--   WHERE NOT EXISTS (SELECT 1 FROM oss_object oo WHERE oo.id = bi.file_id);
--
-- 2) 裸 key 的 site_asset 应当全部拿到 file_id（返回 0）
-- SELECT count(*) FROM site_asset
--   WHERE storage_url IS NOT NULL AND storage_url NOT LIKE 'http%' AND file_id IS NULL;
--
-- 3) 上述三条都为 0、且新版 API 跑稳若干天之后，再执行下面这句让 user_file 正式退场。
--    **不要和本文件一起执行** —— 保留它是回滚的唯一凭据。
-- DROP TABLE user_file;
