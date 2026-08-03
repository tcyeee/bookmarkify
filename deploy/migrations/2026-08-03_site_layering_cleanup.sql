-- 2026-08-03 站点分层收尾（SITE_LAYERING_DESIGN.md §8 第 6 步的一部分）
--
-- ⚠️ **必须在部署新版 API 之后执行。** 与本项目其它迁移的方向相反：
-- 这里删的是列，而线上旧代码仍在读写它们 —— 先删列，运行中的旧实例每次抓取都会炸。
-- 顺序：合并代码 → 部署 → 确认新版起来了 → 才跑本文件。
--
-- 反过来也不能拖太久：新代码不再写 bookmark.nsfw，所以部署后到执行本文件之间，
-- 那一列只是停止更新，不会产生错误数据（读路径已全部改成 site.nsfw）。这段窗口是安全的。
--
-- 执行前的存量核对（2026-08-03 生产库实测）：
--   bookmark.nsfw = true 的行数：0
--   bookmark.nsfw_reason 非空的行数：0
--   bookmark.nsfw=true 而 site.nsfw=false 的行数：0   ← 这条为 0 才说明没有判定会丢失
--   bookmark.site_id IS NULL 的行数：0
-- 若在别的环境执行，请先重跑这四条确认，再往下走。

------------------------------------------------------------------------------
-- 1. 兜底：把任何残留的页面级 NSFW 判定上移到站点级
------------------------------------------------------------------------------
-- 生产库上这条影响 0 行，但别的环境未必。放在 DROP 之前，是为了保证
-- "先不丢信息，再删列" —— 反过来写就是先销毁再补救。
UPDATE site s
SET nsfw = true,
    nsfw_reason = COALESCE(s.nsfw_reason, sub.reason),
    update_time = now()
FROM (
    SELECT site_id, min(nsfw_reason) AS reason
    FROM bookmark WHERE nsfw = true AND site_id IS NOT NULL
    GROUP BY site_id
) sub
WHERE s.id = sub.site_id AND s.nsfw = false;

------------------------------------------------------------------------------
-- 2. 删除页面级 NSFW 副本
------------------------------------------------------------------------------
-- 判定的写入端早已上移到 site（BookmarkServiceImpl.checkNsfw → siteService.markNsfw），
-- 这两列只剩存量。读路径此前是 `site.nsfw OR bookmark.nsfw` 的过渡期双读，
-- 本批同步收敛成 `COALESCE(st.nsfw, false)` 单列（3 处 mapper SQL + Response.kt）。
--
-- 注意 BookmarkAdminVO：它是 BeanUtil 整体拷贝出来的，删列之后拷不到 nsfw，
-- 会**静默**退化成"后台永远标不出违规站点"。代码里已改为按 siteId 显式回填。
ALTER TABLE public.bookmark DROP COLUMN IF EXISTS nsfw;
ALTER TABLE public.bookmark DROP COLUMN IF EXISTS nsfw_reason;

------------------------------------------------------------------------------
-- 3. bookmark.site_id 收紧为 NOT NULL
------------------------------------------------------------------------------
-- 页面必须先有站点：品牌名/图标/NSFW/域名活性全挂在 site 上，没有 siteId 的页面
-- 拿不到任何展示信息，而这种漏挂在编译期看不出来（BookmarkEntity 的构造函数已强制
-- 传 siteId，但那挡不住直接 new 出来的路径）。
ALTER TABLE public.bookmark ALTER COLUMN site_id SET NOT NULL;

------------------------------------------------------------------------------
-- 本批**刻意不动**的三列，别顺手删
------------------------------------------------------------------------------
-- · bookmark.app_name        —— 看着像遗留，实际仍在跨三端使用：管理后台的搜索过滤与
--                               编辑框、字段锁 BookmarkLockedField.APP_NAME、DeepSeek
--                               简称推断的写入端(inferAndSetAppName)，以及前端两处
--                               `item.title || item.appName || item.urlHost` 渲染兜底。
--                               删它是一次跨 API/后台/前端的功能迁移，不是清理，另起一批。
-- · site_asset.bookmark_id   —— 归属看 owner_type/owner_id，这一列答的是另一个问题：
-- · site_display_pref.bookmark_id  "这张图/这次调整当初来自哪个页面"。是溯源事实，
--                               不是与归属重复的第二套真相，删掉是净损失。
