-- ─────────────────────────────────────────────────────────────────────────────
-- 2026-08-18 · 把被判成图标的页面截图改回 SCREENSHOT
--
-- 应用顺序**任意**：这条迁移只是把 role 恢复成抓取当时写下的值，新旧两版代码读到它都更正确。
-- 幂等，可重复执行。
--
-- 病因（完整叙述在 AssetRolePolicy.assignRoles 第一遍的注释里）：
--   截图那一行的 extractor 是我方自造的 'HEADLESS_CAPTURE'，不在 scrapper 契约的
--   AssetExtractor 枚举里。抓取那一刻不会出事（SiteAssetIngestor 直接给它定死 role），
--   但 AssetVerdictRecomputeService 是**按 owner 整组**把库里的行读出来重跑 assignRoles 的，
--   截图行就在那一组里 —— valueOf 抛异常 → UNKNOWN → classify 的兜底 (FAVICON, DEGRADED)。
--
-- 2026-08-17 生产上第一次跑重算之后的实况：
--   role='FAVICON', quality='DEGRADED', owner_type='PAGE' —— 76 行，全部截图，其中 71 行 is_primary。
--
-- 后果不是「截图和 favicon 里挑一个」。截图归 PAGE 层，而 AssetRolePolicy.preferPageOwned
-- 一见到 PAGE 层图标就把站点图标整批筛掉，于是这些页面的图标候选池里**只剩截图**，
-- TILE 和 LIST 一起中招 —— 磁贴上显示的是一张缩得看不清的网页缩略图。
--
-- 另外两处静默损坏也由这次修复一并解掉：
--   · BookmarkServiceImpl.captureScreenshot 的跳过条件是「已有 SCREENSHOT 资产」，role 被改掉
--     之后它永远不成立，这些页面每轮内容重抓都要再付一次 30s 无头；
--   · SiteAssetWriter.dropStalePageIcons 按 role 删 PAGE 层图标（FAVICON/LOGO），会顺手把
--     截图行删掉。
--
-- is_primary 这里一并置为 true：生产实测每个页面恰好一行截图（upsertScreenshot 是删旧插新），
-- 所以它必然是 SCREENSHOT 组里唯一的一张。图标那两层的 primary 由后台
-- 「图标管理 › 重算判定」(POST /admin/icon/recompute-verdict) 顺带纠正 —— 部署新版 API 之后
-- 那个动作已经幂等，且会把任何漏网的截图行自己改回来。
-- ─────────────────────────────────────────────────────────────────────────────

UPDATE public.site_asset
SET role       = 'SCREENSHOT',
    quality    = 'TRUSTED',
    is_primary = TRUE
WHERE extractor = 'HEADLESS_CAPTURE'
  AND (role <> 'SCREENSHOT' OR quality <> 'TRUSTED' OR is_primary IS DISTINCT FROM TRUE);

-- 核对：应当返回 0 行
SELECT role, quality, is_primary, count(*)
FROM public.site_asset
WHERE extractor = 'HEADLESS_CAPTURE'
  AND role <> 'SCREENSHOT'
GROUP BY 1, 2, 3;
