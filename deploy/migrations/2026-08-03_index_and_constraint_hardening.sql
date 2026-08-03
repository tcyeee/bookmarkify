-- 2026-08-03 索引与约束加固
--
-- 起因是一次表设计 review。这批修的**不是设计问题，是缺失的库级保证**：好几处
-- "每 X 至多一行" 只写在 Kotlin 里（`.one()` 一旦查出多行直接抛异常），库上没有任何
-- 东西拦得住并发插出第二行。
--
-- 全部索引用 CONCURRENTLY，不锁表，可在线执行。**注意：CONCURRENTLY 不能在事务块里跑**，
-- 用 psql 直接执行本文件即可（psql 默认 autocommit），不要包 BEGIN/COMMIT。
--
-- 与 API 部署顺序无关：纯加固，不改变任何现有查询的语义，先后随意。
--
-- 已于 2026-08-03 应用到生产库（PostgreSQL 17.4）。执行前的存量数据检查全部通过：
-- 无重复 email（且 6 条读写路径都统一 .trim().lowercase()，无大小写歧义）、
-- 无重复 github_id、user_preference / background_config 每 uid 恰好 1 行、
-- site_display_pref 为空表。

------------------------------------------------------------------------------
-- 1. user_info：登录路径此前既无索引也无唯一约束
------------------------------------------------------------------------------

-- 登录/注册/邮箱验证/Google 回落/GitHub 回落，UserServiceImpl 里 6+ 处
-- `WHERE email = ? AND deleted = false` —— 此前全部顺序扫描，且两个并发注册
-- 同一邮箱都会成功。谓词与查询条件完全对齐，因此这一条同时承担防重与加速。
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_user_info_email
    ON public.user_info (email) WHERE email IS NOT NULL AND deleted = false;

-- 与 uk_user_info_google_id 对称。google 有、github 没有是遗漏而非取舍。
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_user_info_github_id
    ON public.user_info (github_id) WHERE github_id IS NOT NULL AND deleted = false;

-- 把既有的 uk_user_info_google_id 也对齐到同一套谓词。原来它缺 `deleted = false`，
-- 于是一个软删账号会**永久**占住那个 Google ID 无法重新注册，而 email / github 两条却可以 ——
-- 三条同类约束语义不一致，且不一致的那一条是最严的，用户侧表现为"这个 Google 账号登不进来了"。
-- 先建后删再改名，中途始终有唯一约束在生效（放宽方向不会因存量数据失败）。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname = 'uk_user_info_google_id' AND i.indpred IS NULL
    ) THEN
        EXECUTE 'CREATE UNIQUE INDEX uk_user_info_google_id_new ON public.user_info (google_id)
                 WHERE google_id IS NOT NULL AND deleted = false';
        EXECUTE 'DROP INDEX uk_user_info_google_id';
        EXECUTE 'ALTER INDEX uk_user_info_google_id_new RENAME TO uk_user_info_google_id';
    END IF;
END $$;

-- 每个匿名访客的 /auth/track 都按 device_id 查一次。不唯一：同设备可留有历史软删行。
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_user_info_device
    ON public.user_info (device_id) WHERE device_id IS NOT NULL AND deleted = false;

------------------------------------------------------------------------------
-- 2. 把代码里的 .one() 假设变成库级约束
------------------------------------------------------------------------------

-- 两处都是 ktQuery().eq(uid).one()：多行即抛异常。也就是说"每 uid 至多一行"本来就是
-- 硬假设，只是从来没人保证过 —— 并发首次访问可以插出两行，此后该用户永久 500，
-- 而且没有任何一条日志会指向根因。
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_user_preference_uid
    ON public.user_preference (uid);
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_background_config_uid
    ON public.background_config (uid);

------------------------------------------------------------------------------
-- 3. 按 uid 列表查询、天然可多行的表
------------------------------------------------------------------------------

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_background_image_uid
    ON public.background_image (uid);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_background_gradient_uid
    ON public.background_gradient (uid);
-- LayoutNodeFunctionServiceImpl 按 uid 查，不是按 layout_node_id
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_lnf_uid
    ON public.layout_node_function (uid);

------------------------------------------------------------------------------
-- 4. site_display_pref.site_id 置 NOT NULL
------------------------------------------------------------------------------

-- 唯一索引 uk_site_display_pref_owner 建在 (site_id, display_mode) 上，而 PG 的唯一索引
-- 不约束 NULL —— site_id 可空意味着同一站点可以有任意多行 TILE 偏好，
-- SiteAssetResolver.resolveBatch 取到哪一行取决于物理顺序。
-- Kotlin 侧 SiteDisplayPrefEntity.siteId 本就是非空 String，这里只是让库跟上。
ALTER TABLE public.site_display_pref ALTER COLUMN site_id SET NOT NULL;
