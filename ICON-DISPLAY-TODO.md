# 图标展示改造 TODO

> 决策日期：2026-08-17 · 状态：**阶段一已完成（代码已改，迁移待部署后应用），阶段二待讨论**
> 范围：`site_asset` 的取图与渲染链路、`site_display_pref` 的移除

---

## 0. 决策

1. **图标质量走「规则」路线，不做用户投票。** 投票暂缓，不是暂缓实现，是暂缓决定要不要做。
2. **移除管理员自定义图标（内边距 / 背景色 / 钉图）整条链路，含数据库表。** —— 阶段一，优先。
3. **把图标展示提取为独立功能板块**，规则细节下次讨论。 —— 阶段二。

### 为什么是规则而不是投票

- **覆盖面差一个量级，且差距永不消失。** 规则是纯函数，改一次同时作用于全部站点**以及所有还没被收藏的站点**；投票只覆盖被投过的那一个，而用户对图标质量的感受由「刚添加的那一条」决定，那一条的票数恒为 0。冷启动不是第一期的问题，是结构性的。
- **现在的问题不是规则不完善，是规则错了，而且错得很便宜。** 生产 82% 的色块率不是审美长尾，是三个机械缺陷（见 §3.1）。在坏规则上叠投票 = 请用户手工修 126 个站点，而其中大部分一次函数改动就全修了；且规则修好后那些票会变成无法解释的历史包袱（分不清「用户在纠正 bug」还是「用户真的偏好这张」）。
- **投票 / 钉图的标的当前不稳定。** `SiteAssetWriter.replaceLayer`（`SiteAssetWriter.kt:289-294`）在资产集合有任何变化时**整层删除重插，新行是新 id**；而 `AssetRolePolicy.resolve` 里是 `usable.firstOrNull { it.id == pinned }`，匹配不上就静默回落自动排序 —— 没有报错、没有日志。将来真要做用户级覆盖，**键必须是 `content_hash`**（`divergesFromSite` 已经在用哈希做跨抓取比对，是现成的稳定身份）。



---

## 1. 生产基线（2026-08-17 实测）

下次讨论规则时以这组数字为起点，改完后用同样的查询复测。

**取图结果**（153 个站点，按 `AssetRolePolicy` 当前逻辑在库里重跑）：

| 情况                                                    | 站点数 | 占比 |
| ------------------------------------------------------- | ------ | ---- |
| 正常显示图片                                            | 27     | 18%  |
| 选中的图 ≥128px，但因 `quality=DEGRADED` 被判首字母色块 | **40** | 26%  |
| 选中的图确实太小（<128px）                              | 86     | 56%  |

在那 86 个里，**31 个站点库里就躺着一张合格的图**（矢量或 TRUSTED ≥128px），只是 `resolve` 根本没看到它。

**候选图数量分布**（按 `content_hash` 去重的 SITE 层 FAVICON/LOGO）：

| 可选图标数 | 1    | 2    | 3    | 4    | 5    | 6+   |
| ---------- | ---- | ---- | ---- | ---- | ---- | ---- |
| 站点数     | 90   | 16   | 12   | 12   | 7    | 16   |

59% 的站点只有一张图，**选无可选**——这也是「用户在候选里选」这条路收益有限的直接证据。

**`site_display_pref`：总行数 0**（调过内边距 0 / 调过背景色 0 / 钉过图 0）。

<details>
<summary>复测用 SQL</summary>


```sql
WITH a AS (
  SELECT owner_id, role, quality,
         CASE WHEN is_vector THEN 2147483647
              ELSE LEAST(COALESCE(width,0), COALESCE(height,0)) END AS sz,
         is_vector
  FROM site_asset
  WHERE owner_type='SITE' AND role IN ('FAVICON','LOGO') AND error_msg IS NULL
), chosen AS (
  SELECT DISTINCT ON (owner_id) * FROM a
  ORDER BY owner_id, (role='LOGO') DESC, (quality='TRUSTED') DESC, sz DESC
)
SELECT CASE
  WHEN is_vector OR (quality='TRUSTED' AND sz>=128) THEN '正常显示图片'
  WHEN quality='DEGRADED' AND sz>=128            THEN '够大但因DEGRADED被判色块'
  ELSE '尺寸确实不够(<128)' END AS 情况, count(*)
FROM chosen GROUP BY 1 ORDER BY 2 DESC;
```

`chosen` 的 `ORDER BY` 复刻的是 `resolve` 在 TILE 模式下的行为：roleOrder 是 `[LOGO, FAVICON]`，LOGO 层非空即 `return`。
</details>

---

## 2. 阶段一：移除管理员自定义图标 —— **已完成**

> 实施日期：2026-08-17 · 迁移：`deploy/migrations/2026-08-17_drop_site_display_pref.sql`（**待部署后应用**）
>
> 与计划的三处出入，都是实施时才看清的：
>
> 1. **后台此前根本改不了 `appName`。** §2.0① 假设「删端点前把 appName 搬走」，但
>    `updateBookmarkIconApi` 在后台**没有任何调用方**（§2.2 自己也标了「死代码」）—— 也就是说
>    那个字段在后台一直是只读的。所以除了服务端搬家，还给「修改基础信息」弹窗补了一个简称输入框
>    （`views/website/page/index.vue`），否则「后台仍能编辑书签简称」这条验收项无从谈起。
> 2. **`adminUpdateBasicInfo` 从 `updateById` 改成逐列显式 `set`。** 简称清空要写 NULL、
>    解掉最后一把锁时 `locked_fields` 也归 NULL，而 `updateById` 跳过 null 字段 —— 沿用它的后果
>    不是报错，是「清空按钮点了没反应」和「锁永远解不掉」。
> 3. **web 侧文件已不在计划写的路径上。** `components/launchpad/cell/BookmarkLogo.vue` 现在是
>    `components/BookmarkLogo.vue`，且 `d6bb231c` 刚给它加了「从图标取主色当底色」
>    （`surfaceColor`）。删掉 `customBgColor` 后那条自动取色分支自然上位，无需其它改动。

### 实测结果

- `./gradlew test`：**237 通过 / 0 失败**，`RegistryCoverageTest`(5) 与 `PageConstraintTest`(6) 均实际跑到
  （`build/test-results/test/*.xml` 逐个核对，非"BUILD SUCCESSFUL 即通过"）。
  注：`bookmarkify-api/CLAUDE.md` 里写的 235 是旧数，本次删掉 1 条钉图用例后仍为 237。
- admin `pnpm typecheck`：`src/` **0 条**，`packages/` 7 条（上游既有，未变）。
- web `pnpm typecheck`：**0 条**。

---

### 原计划（保留备查）

### 2.0 三条边界 —— 什么**不**删

**① `page.app_name` 必须活下来，且要先搬家。**
`BookmarkIconUpdateParams` 里捎带了 `appName`，但它跟图标外观无关——它是 TILE 标题的候选来源（`2026-08-16_bookmark_pinned_sort.sql` 那批加的，生产 92 个首页里 75 个靠它出标题，`site.short_name` 只有 15 个）。删端点前，把 `appName` 的编辑连同 `PageLockedField.APP_NAME` 的加锁/解锁逻辑一起搬进 `BookmarkBasicInfoUpdateParams` / `adminUpdateBasicInfo`。**漏了这条 = 后台再也改不了书签简称，而且不会报错。**

**② 后台「显示设置」卡片的读侧要留，只删写侧。**
`SiteDisplayPrefVO` 里的 `previewUrl` / `monogram` **不来自 pref 表**，来自 `SiteAssetResolver` —— 它回答的是「这个书签在 TILE / LIST 两种模式下实际会渲染成什么」，正是阶段二排规则时最需要的东西。做法：砍掉 `iconPadding` / `iconBgColor` / `pinnedAssetId` 三个字段，VO 与卡片一并改名（建议 `IconRenderVO` / 「渲染结果」），保留按模式分行的展示。

**③ `AssetRolePolicy` 的判定逻辑一行不动。**
只删 `resolve` 的 `pinnedAssetId` 参数与它那段短路，`TABLE` / `assignRoles` / `shouldFallbackToMonogram` 全部留到阶段二。两件事分开提交，否则回归对不上——阶段二要用阶段一之后的数字做基线。

### 2.1 后端（bookmarkify-api）

| 文件                                                         | 动作                                                         |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| `controller/admin/AdminBookmarkManageController.kt:45-49`    | 删 `POST /admin/bookmark/{pageId}/icon`                      |
| `entity/Request.kt:58-65`                                    | 删 `BookmarkIconUpdateParams`（`appName` 先搬到 `BookmarkBasicInfoUpdateParams`，见 §2.0①） |
| `entity/Response.kt:526-534`                                 | `SiteDisplayPrefVO` 砍成 `displayMode` + `previewUrl` + `monogram` 并改名 |
| `entity/Response.kt:316`                                     | `BookmarkAdminVO.displayPrefs` 随之改名                      |
| `entity/Response.kt:151-159`                                 | `BookmarkLogoShowVO` 删 `iconPadding` / `iconBgColor` 两个字段 |
| `entity/Response.kt:1105`                                    | `OrphanCleanupReport.displayPrefs` 计数字段删除              |
| `entity/entity/SiteAssetEntity.kt:120-155`                   | 删 `SiteDisplayPrefEntity`（含 `DEFAULT_ICON_PADDING`）      |
| `mapper/SiteAssetMapper.kt:9,22`                             | 删 `SiteDisplayPrefMapper`                                   |
| `server/asset/SiteDisplayPrefService.kt`                     | **整个文件删除**                                             |
| `server/asset/SiteAssetResolver.kt`                          | 删 mapper 注入(36)、`ResolvedLogo` 的 padding/bgColor(54-55)、`prefsOfBatch`(181)、`prefOf`(191)、`prefsBySite`(262)，以及 `build` 里三处 pref 读取(293-324) |
| `server/asset/AssetRolePolicy.kt:219,222-232`                | 删 `resolve` 的 `pinnedAssetId` 形参与那段短路               |
| `server/admin/BookmarkAdminService.kt`                       | 删 `adminUpdateIcon`(158-184)、注入(66)、import(29,41)；`adminListAll`(94-105) 与 `adminDetail`(509-516) 改为只下发渲染结果 |
| `server/impl/BookmarkServiceImpl.kt:37,93`                   | **未使用的注入**，直接删（现在就是死代码）                   |
| `server/repair/OrphanCleanupService.kt:16,28,83,131,219-221` | 删 `OWNERSHIP_REGISTRY` 条目、mapper 注入、`purge` 段        |
| `server/asset/SiteAssetWriter.kt:29`                         | 注释里「永不触碰 site_display_pref」的说明改写               |
| `entity/entity/PageEntity.kt:49`                             | 注释提及 `site_display_pref`，改写                           |

**测试：**

- `AssetRolePolicyTest.kt:252` 有一条 `pinnedAssetId` 用例，删。
- `RegistryCoverageTest` 反射扫 `@TableName` 实体，实体消失后应自动通过——但**必须实际跑一遍**，它是这次删除唯一的自动化守卫。
- 跑完 `./gradlew test`，确认 235 条基线没掉。

### 2.2 前端 · admin（bookmarkify-admin）

| 文件                                                         | 动作                                                         |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| `src/api/bookmark.ts:82-90`                                  | `SiteDisplayPref` 接口砍字段并改名                           |
| `src/api/bookmark.ts:255-267`                                | 删 `updateBookmarkIconApi`（**当前已无任何调用方**，是死代码） |
| `src/api/bookmark.ts:171`                                    | `displayPrefs` 字段随后端改名                                |
| `src/views/bookmark/siteAsset.ts:44-63`                      | `prefOf` 删除（**无调用方**）；`lacksRealLogo` 一并检查（同样无调用方） |
| `src/views/bookmark/BookmarkDetailDialog.vue:1097-1130`      | 「显示设置」卡片改造为只读的「渲染结果」，去掉「已钉图」标签 |
| `src/views/bookmark/BookmarkDetailDialog.vue:266,579-581`    | 随字段改名调整                                               |
| `src/api/bookmark-cleanup.ts:32` + `src/views/system/config/BookmarkCleanupSection.vue:43` | 删「站点展示偏好」这一行计数                                 |

改完跑 `pnpm typecheck`（`src/` 必须保持 0 错误，它是 CI 门禁）。

### 2.3 前端 · web（bookmarkify-web）

| 文件                                                 | 动作                                                         |
| ---------------------------------------------------- | ------------------------------------------------------------ |
| `typing/bookmark.ts:35-36`                           | `BookmarkLogo` 删 `iconPadding` / `iconBgColor`              |
| `components/launchpad/cell/BookmarkLogo.vue:78-80`   | 删 `customBgColor` / `effectivePadding`                      |
| `components/launchpad/cell/BookmarkLogo.vue:130-142` | `logoStyle` 里的自定义底色分支删除（保留本地/IP 的灰底分支）；`imageStyle` 的 shrink 计算恒为 1，简化成铺满 |
| `components/launchpad/cell/BookmarkLogo.vue:153-164` | `monogramStyle` 里 `customBgColor ||` 短路删除               |

> **渲染结果零变化。** `DEFAULT_ICON_PADDING` 已经是 0（commit `df3168ab`），背景色因为表是空的恒为 null，所以这一步是纯删除，生产上一个像素都不会变。顺带说明：`ee14aafa` / `df3168ab` 这两笔围绕内边距的调整在删除后自然失去意义，属于预期内。

> `BookmarkLogo.vue:4` 的 `bg-gray-100` 与 `:7` 的 `bg-white` **本阶段不动**——它们是「透明背景渲染不出来」的根因，归阶段二。

### 2.4 数据库

新建 `deploy/migrations/2026-08-17_drop_site_display_pref.sql`：

```sql
DROP INDEX IF EXISTS uk_site_display_pref_owner;
DROP TABLE IF EXISTS public.site_display_pref;
```

**⚠️ 这条迁移必须在部署 API 之后应用**，与本仓库绝大多数迁移方向相反（同 `2026-08-03_site_layering_cleanup.sql` 的情形）。运行中的旧 jar 仍在 SELECT 这张表，而它位于 `SiteAssetResolver.resolveBatch` 上——那是**整个桌面的读路径**，先 drop 会让每个用户的 `/bookmark/query` 直接抛异常。

表是空的，所以「部署后、迁移前」这段窗口没有任何数据风险：新代码不读它，旧代码读到的也一直是空集。

配套：

- 刷新 `deploy/schema.sql`（删 421-434 的建表、706-710 的主键、1111-1114 的唯一索引）。schema.sql 是**结构的记录本**，任何结构变更后都要刷。
- `deploy/scripts/clean_bookmarks_and_sites.sql:21,26,75` 删掉对该表的 `TRUNCATE` 与计数。
- 历史迁移文件 `2026-08-16_icon_padding_default_zero.sql` **保留不动**（迁移是变更记录，不是当前状态）。

### 2.5 文档

`CLAUDE.md` 与 `AGENTS.md` 是镜像的，**每处都要改两遍**：

| 文件                                                      | 位置                                                         |
| --------------------------------------------------------- | ------------------------------------------------------------ |
| `CLAUDE.md` / `AGENTS.md`                                 | 143（四表对照）、145（crawl 边界）、160（按模式分行的理由）、207（目录树）、242（索引硬化迁移的描述） |
| `bookmarkify-api/CLAUDE.md` / `bookmarkify-api/AGENTS.md` | 201（表清单）                                                |
| `SITE_LAYERING_DESIGN.md`                                 | §5（168-183）、264、345、355 —— 这是设计记录，建议**加一段「2026-08-17 已移除」的后记**而不是删掉原文 |
| `ADD-BOOKMARK-FLOW.md:301`                                | 第 12 条提到「真要做用户级图标覆盖时这里需要一次迁移」，改为指向本文档 |

### 2.6 验收

- [x] `./gradlew test` 通过，`RegistryCoverageTest` 实际跑到（237/0，见上方「实测结果」）
- [x] admin `pnpm typecheck` 的 `src/` 仍为 0 错误
- [x] web `pnpm typecheck` 仍为 0 错误
- [x] 后台书签详情页仍能看到两种模式的渲染结果与「首字母色块」标签（卡片改名「渲染结果」）
- [x] 后台仍能编辑书签简称（`app_name`），且编辑后 `locked_fields` 正确加锁
      —— 编辑框是这次**新加**的，见上方出入①
- [x] `deploy/schema.sql` 已刷新（建表 / 主键 / 唯一索引三段均已删）
- [ ] **部署 API → 再应用 drop 迁移**（顺序不可颠倒，见 §2.4）
- [ ] 用 §1 的 SQL 复测，三档数字不变（27 / 40 / 86）——阶段一不应改变任何取图结果

> 最后两项要等这批代码上线才能做，代码侧到此为止。

---

## 3. 阶段二：图标展示独立板块（待讨论）

**先不动手。** 这里只记录已经查清的输入，供下次讨论。

### 3.1 已定位的三个缺陷

**① `shouldFallbackToMonogram` 把两种判断用 `||` 连起来了**（`AssetRolePolicy.kt:337`）

```kotlin
return chosen.quality == AssetQuality.DEGRADED || chosen.effectiveSize() < TILE_MIN_SIZE
```

`DEGRADED` 是**出处判断**（「这不是品牌 LOGO，只是 favicon 换了个 rel」），`< 128` 是**渲染判断**（「放大会糊」）。只有后者是拒绝显示图片的理由：一张 1024px 的 apple-touch-icon 放在 72px 磁贴上非常好看，它算不算「真 logo」与此无关。`appstoreprice.org` 即是——选中 1024px，因 hash 撞 favicon 被降级，于是渲染首字母。**影响 40 个站点。**

**② 借用机制在 TILE 模式下是净负收益**（`assignRoles` 第三遍 `:186-195` + `resolve` 的 roleOrder `:234-251`）

没有可用 LOGO 时借一张 apple-touch-icon 改判成 LOGO 并标 DEGRADED。而 TILE 的 `roleOrder = [LOGO, FAVICON]`，LOGO 层一非空就 `return`，**永远不下探 FAVICON 层**；借来的必然 DEGRADED（库里仅 5 张矢量例外），于是必然触发色块。结果是：借用没救成任何站点，反而挡住了旁边那张又大又 TRUSTED 的图。

**③ 借用取的是「第一张匹配 extractor 的」，不是「最大的一张」**（`AssetRolePolicy.kt:189-191`）

站点声明多张不同尺寸的 apple-touch-icon 是常态，`firstOrNull { it.extractor == wanted }` 抓到哪张纯看列表顺序：

```
live.bilibili.com   借到 32px   ←→  同族 512px 那张留在 FAVICON 层
www.jianshu.com     借到 57px   ←→  152px
www.chiphell.com    借到 0px    ←→  LINK_MASK_ICON svg
tool.lu             借到 57px   ←→  144px
```

②③ 合计影响 31 个站点（与①有重叠）。三条都是**纯函数改动**：不改表、不改契约、不重抓，`site_asset` 已存着全部候选，改判对存量立刻生效。

### 3.2 待讨论的问题

- **板块边界。** `AssetRolePolicy` + `SiteAssetResolver` + `BookmarkLogoShowVO` + web `BookmarkLogo.vue` 是否合成一个显式模块，以及模块的输入/输出契约怎么定。
- **`TILE_MIN_SIZE = 128` 这个阈值本身**是否还成立（磁贴实际尺寸 56–80px，2× DPI 下 160px 就够）。
- **尺寸未知怎么处理。** `effectiveSize()` 在 `width`/`height` 为 null 时返回 0，直接判死。当前只影响个位数资产（31 个尺寸未知的 TRUSTED FAVICON 里 30 个是矢量），但 `gitlab.com` 的 `JSON_LD_ORG_LOGO` 就栽在这。
- **padding / 背景是否改为自动推导。** 边缘透明像素占比 → padding；主色 → 「同色调」背景。都该在 `SiteAssetIngestor` 落库时算好存进 `site_asset`（前端从签名 URL 取色会被 CORS 挡住，canvas 会被污染）。
- **「透明」背景当前渲染不出来**：`BookmarkLogo.vue:4` 的 `bg-gray-100` 与 `:7` 的 `bg-white` 写死。真要透出桌面壁纸，两层都得由字段驱动。
- **用户级图标覆盖做不做、什么时候做。** 若做：键用 `content_hash`（理由见 §0），且它的第一价值是**产出带标注的分歧样本**（后台一张「用户选择 vs 规则选择」对照表），而不是每站点一个覆盖值。
- **后台需要一个「规则判定结果」总览页**，把 §1 那张表做成页面，这样每次规则改动的效果可以直接量出来（27 → ?）而不用连生产库跑 SQL。
