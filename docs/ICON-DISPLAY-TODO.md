# 图标展示改造 TODO

> 状态：**阶段一、二均已完成（2026-08-17），阶段二待上线 —— 上线顺序见 §5 末尾**
> 范围：`site_asset` 的取图、按展示尺寸的适配、图标自身的留白与底色，以及把这一整块**解耦成独立板块**

---

## 0. 决策

1. **图标质量走「规则」路线，不做用户投票 / 不做管理员逐站点微调。**
2. **阶段二的目标：让代码决定图标怎么显示。** 三个功能问题见 §3。
3. **同时把图标这一块从前后端各自的大类/大文件里抽出来。** 解耦步骤见 §4，执行顺序见 §5。

### 为什么是规则而不是人工

- **覆盖面差一个量级，且差距永不消失。** 规则是纯函数，改一次同时作用于全部站点**以及所有还没被收藏的站点**；人工设置只覆盖被调过的那一个，而用户对图标质量的感受由「刚添加的那一条」决定，那一条的人工值恒为空。
- **现在的问题不是规则不完善，是规则错了，而且错得很便宜。** 生产 83% 的色块率不是审美长尾，是三个机械缺陷（见 §3.1）。在坏规则上叠人工修正 = 请人手工修 136 个站点，而其中 52 个一次函数改动就全修了（§3.1 的实测上限）。
- **人工钉图的标的当前不稳定。** `SiteAssetWriter.replaceLayer` 在资产集合有任何变化时**整层删除重插，新行是新 id**，钉的 id 匹配不上就静默回落。将来真要做用户级覆盖，**键必须是 `content_hash`**（`divergesFromSite` 已经在用哈希做跨抓取比对，是现成的稳定身份）。

> ⚠️ 上面第一条现在**只对读侧成立**。`role` / `quality` 是在抓取时算好、写进 `site_asset` 列里的，所以改 `assignRoles` 不会自动作用于存量 —— 详见 §3.0，这是阶段二必须先解决的一个前提。

---

## 1. 生产基线

> **2026-08-17 复测：三档数字未漂（27 / 40 / 86），但基线本身有两处错，已在下方订正。**
> 现在这组数字有页面了 —— 后台「网站管理 › 图标判定总览」，不必再连生产库敲 SQL。

**取图结果**（按 `AssetRolePolicy` 当前逻辑在库里重跑）：

| 情况 | 站点数 | 占比 | 其中「库里有合格候选」 |
| --- | --- | --- | --- |
| 正常显示图片 | 27 | 17% | — |
| 选中的图 ≥128px，但因 `quality=DEGRADED` 被判首字母色块 | **40** | 25% | **19** |
| 选中的图确实太小（<128px） | 86 | 53% | **12** |
| **有资产行、但没有一张能渲染** | **10** | 6% | 0 |
| 合计参与判定 | **163** | | **31** |

另有 **24 个站点一行图标资产都没有**（`site` 表共 187 行），不参与判定 —— 那是抓取的问题，不是选图规则的问题。

**订正一：分母是 163，不是 153。** 原基线的 SQL 在 `DISTINCT ON` **之前**就 `WHERE error_msg IS NULL`，于是 10 个「抓到了图但没有一张能渲染」的站点被整体筛掉了，连同它们那 6% 一起从分母里消失。这 10 个站点在桌面上同样是首字母色块，把它们排除在外会让「正常显示率」偏高。

**订正二：那 31 个「有合格候选」不全在 86 那一档。** 原文写「在那 86 个里，31 个站点库里就躺着一张合格的图」，实测是 **86 档 12 个、40 档 19 个**。这个分布直接决定了 §3.1 各条修复的收益归属，见 §3.1 的「预期上限」。

**候选图数量分布**（按 `content_hash` 去重的 SITE 层 FAVICON/LOGO）：

| 可选图标数 | 1 | 2 | 3 | 4 | 5 | 6+ |
| --- | --- | --- | --- | --- | --- | --- |
| 站点数 | 90 | 16 | 12 | 12 | 7 | 16 |

59% 的站点只有一张图，**选无可选**——这也是「让用户在候选里选」这条路收益有限的直接证据。

<details>
<summary>复测用 SQL（保留备查；日常复测请用后台页面，它走的是线上同一份代码而不是这段复刻）</summary>

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

## 2. 阶段一（已完成，2026-08-17）

移除了管理员自定义图标（内边距 / 背景色 / 钉图）整条链路，含 `site_display_pref` 表——该表在生产是**零行**。迁移 `deploy/migrations/2026-08-17_drop_site_display_pref.sql`（**必须在部署 API 之后应用**，旧 jar 仍在桌面读路径上 SELECT 它）。

顺带的两笔：`page.app_name` 的编辑从已删除的图标端点搬进了 `adminUpdateBasicInfo`（并给后台补了一个简称输入框——此前那个字段在后台一直是只读的）；web 侧删掉 `customBgColor` 后，`d6bb231c` 加的「从图标取主色当底色」（`surfaceColor`）自然上位。

**渲染结果零变化**，生产复测三档数字一字不差（27 / 40 / 86），日志无异常。`AssetRolePolicy` 的判定逻辑一行未动——这是刻意的，阶段二要用阶段一之后的数字做基线。

---

## 3. 阶段二的三个功能问题（均已完成）

### 3.0 前提：判定被写死在抓取时，改规则不会作用于存量

`AssetRolePolicy` 的两个入口落在**链路两端**，这件事此前没有写下来，而它决定了每条修复的生效方式：

| 入口 | 调用方 | 何时生效 |
| --- | --- | --- |
| `assignRoles`（定 role / quality / isPrimary） | `SiteAssetIngestor:85` —— **写路径** | 改了要**重抓**才生效 |
| `resolve` / `shouldFallbackToMonogram` / `preferPageOwned` | `SiteAssetResolver` —— **读路径** | 改了对存量**立刻**生效 |

于是 §3.1 的三个缺陷分属两侧：**① 在读侧，改完立刻见效；②③ 在写侧，不处理存量就是白改。** 而内容重抓周期是 30 天，等自然重抓等于让那 12 个站点再错一个月。

根子上这是一个分层错误：`site_asset` 全文档都写着「只存抓取事实」，而根 `CLAUDE.md` 的对照表也明说 `role` / `quality` 是**判断**、归 API。把判断物化进事实表，正是「改一次规则作用于全部站点」这条前提失效的唯一原因。

两条路：

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| **A. 保留列，加一条「重算判定」** | 对存量行重跑 `assignRoles` 并回写，不重抓（`site_asset` 已经存着 `extractor` / `contentHash` / 尺寸，全部输入都在） | 改动小；`assetsByBookmark` 的 `orderByAsc(role)`、`siteFaviconByHost` 的 `in(role, …)` 等 SQL 过滤都能留着；后台资产列表仍能显示存下来的判定 |
| **B. role/quality 改为读时现算** | 只在写时定 `ownerType`/`ownerId`，其余全部由 `extractor` + `contentHash` + 同批兄弟行现推 | 最彻底，规则改动零延迟；但 SQL 里所有按 role 过滤/排序的地方都要改成取回后在内存做，后台「这张图被判成什么」也变成现算 |

**采用 A。** B 的正确性更高，但它把一次规则修复变成一次读路径重构，而读路径正是 §3.2 要改的地方，两件事叠在一起没法分开验收。A 保留了 B 的全部退路：重算入口本身就是「把判定当派生值对待」的具体实现，将来要转 B 只是把回写去掉。

重算入口做成**后台一个按钮 + 一条可重跑的脚本**（幂等，按 `owner_id` 分批），而不是启动时自动跑：它会改写全站图标的显示结果，必须是一个有人按下、有日志、可复测的动作。

> `ownerType` 不受影响：它只依赖 `classify(extractor)` 这一层的基础分类。`SiteAssetIngestor:83` 那句「归属必须在 assignRoles 之后算」在注释里已经承认结论不变（借用只把 FAVICON 改成 LOGO，两者都归 SITE）。重算不动归属，因此不触发 `replaceLayer` 的整层删重插，也就不会造出新 id。

### 3.1 问题一：多张候选图里选哪一张 —— **已全部修完（2026-08-17）**

四个缺陷（第 ④ 条是修完前三条后用生产数据模拟才浮出来的）。**它们是串联的**，这点比单独看每一条更重要 —— 而且四条的根子是同一句话：**出处/用途是偏好，尺寸是硬要求**，把偏好放在硬要求前面，结果就是宁可退成首字母色块也不用旁边那张大图。

落地过程与最终数字见 §5 的落地记录（27 → 79，改进空间归 0）。

**① `shouldFallbackToMonogram` 把两种判断用 `||` 连起来了**（`AssetRolePolicy.kt:336`，**读侧**）

```kotlin
return chosen.quality == AssetQuality.DEGRADED || chosen.effectiveSize() < TILE_MIN_SIZE
```

`DEGRADED` 是**出处判断**（「这不是品牌 LOGO，只是 favicon 换了个 rel」），`< 128` 是**渲染判断**（「放大会糊」）。只有后者是拒绝显示图片的理由：一张 1024px 的 apple-touch-icon 放在 72px 磁贴上非常好看，它算不算「真 logo」与此无关。**影响 40 个站点。**

**② 借用机制在 TILE 模式下是净负收益**（`assignRoles` 第三遍 `:186-195` + roleOrder `:233-236`，**写侧**）

`TABLE` 里 `APPLE_TOUCH_ICON → (FAVICON, TRUSTED)`。没有可用 LOGO 时，第三遍借它顶上，**改写成 `(LOGO, DEGRADED)`** —— 一张本来 TRUSTED 的资产被主动降级了。而 TILE 的 `roleOrder = [LOGO, FAVICON]`，LOGO 层一非空就 `return`，**永远不下探 FAVICON 层**；于是这张 DEGRADED 的图必然撞上缺陷 ①，渲染成色块。

**借用把一张好图降级、挡住了旁边更好的图、再被自己降的级杀掉。** 这就是那 40 个「够大却显示色块」站点的成因。

**③ 借用取的是「第一张匹配 extractor 的」，不是「最大的一张」**（`AssetRolePolicy.kt:189-191`，**写侧**）

站点声明多张不同尺寸的 apple-touch-icon 是常态，`firstOrNull { it.extractor == wanted }` 抓到哪张纯看列表顺序：

```
live.bilibili.com   借到 32px   ←→  同族 512px 那张留在 FAVICON 层
www.jianshu.com     借到 57px   ←→  152px
www.chiphell.com    借到 0px    ←→  LINK_MASK_ICON svg
tool.lu             借到 57px   ←→  144px
```

**④ `roleOrder` 与层内排序把用途/可信度当成了绝对闸门**（`resolve` 的循环 + `compareBy{quality}.thenBy{size}`，**读侧**）

循环一旦在 LOGO 层拿到候选就 `return`，**永不下探 FAVICON**：`gitlab.com` 一张尺寸未知的 JSON-LD logo 挡住了两张 192px favicon，`element.eleme.cn` / `www.chiphell.com` 的小 LOGO 挡住了矢量 mask-icon。层内同理 —— `hellogithub.com` 的 TRUSTED 48px 压过了 DEGRADED 192px。
修法：TILE 下先按 `qualifiesForTile` 筛一道，role/quality 只在**撑得起大图的候选之间**做偏好；一张都不够大时退回原池。**影响 6 个站点。**

**修的顺序是 ②③ 先于 ①。** 把借用改成「不改 quality、且取同族最大的一张」之后，① 那个 `||` 影响的样本会明显缩小，`TILE_MIN_SIZE` 该不该动才量得准。②③ 改完必须跑一次 §3.0 的重算，否则数字不会动。

**预期上限（2026-08-17 用生产数据算的，不是估计）：**

| 修到哪一步 | 正常显示 | 说明 |
| --- | --- | --- |
| 现状 | 27 / 163（17%） | |
| 只修 ① | **67**（41%） | MONOGRAM_QUALITY 那 40 个选中的图**按定义**就 ≥128px，去掉 quality 那半个判据即全部转正 |
| 再修 ②③④ | **79**（48%） | MONOGRAM_SIZE 的 86 个里，有 **12** 个库里存在 ≥128px 的可渲染图（其中 6 个要靠 ④ 才够得着） |
| 天花板 | 79 | 剩下 74 个 MONOGRAM_SIZE + 10 个 NO_ASSET **库里没有任何够大的图**，规则再怎么改也救不了；它们要靠抓取侧多拿到几张图 |

所以 §3.1 的三条合起来把正常显示率从 17% 推到 48%，其中 **① 一条就占了 40/52 的收益** —— 而它是三条里唯一的读侧改动，不需要重算、不需要重抓，改完立刻生效。这也是把它排在最前面的理由。

**待定的两个参数，都留到 ①②③ 修完再定：**

- **`TILE_MIN_SIZE = 128` 是否还成立。** 磁贴实际尺寸 56–80px，2× DPI 下 160px 才是真正需要的，128 反而偏松；但它同时是「宁可色块也不拉伸」的闸门，收紧会让色块变多。现在量到的是三个缺陷的和，改这个数没有意义。
- **尺寸未知怎么处理。** `effectiveSize()` 在 `width`/`height` 为 null 时返回 0，直接判死。当前只影响个位数资产（31 个尺寸未知的 TRUSTED FAVICON 里 30 个是矢量），但 `gitlab.com` 的 `JSON_LD_ORG_LOGO` 就栽在这。

### 3.2 问题二：前端有多个显示尺寸，怎么适配 —— **已修完（2026-08-17）**

**当前只有两档，而且两档整体挂反了界面。**（2026-08-17 逐个调用点核对）

服务端 `DisplayMode` 只有 `TILE` / `LIST`，`renderSize` 分别签 256px / 64px。前端 `BookmarkLogo` 的 `SIZE_PRESETS` 是 `S: 20` / `M: 56`。实际对应关系是：

| 界面 | 服务端 mode | 前端渲染尺寸 | 对不对 |
| --- | --- | --- | --- |
| 设置页 › 书签库（`BookmarkLibrary.vue`） | **TILE** | 28px | ✗ 用 256px 的签名图喂一个 28px 的格子，还会因 monogram 判断把本来够用的小 favicon 换成色块 |
| 分享页 / 分享编辑（`share/[code].vue`、`share/edit.vue`） | **TILE** | 20px (S) | ✗ 同上，更极端 |
| 桌面树行（`BookmarkTreeRow.vue`、`index.vue`） | LIST | 20px (S) | ✓ |
| **置顶区磁贴（`PinnedBookmarkGrid.vue`）** | **LIST** | **56px (M)** | ✗ 全站唯一的大图位，却拿着小图那一档 |

**TILE 这一档（LOGO 优先、256px、带 monogram 兜底）目前只服务 20–28px 的格子，而全站唯一的 56px 大图位跑在 LIST 上。** 这不是「置顶区少算了一份」，是两档整体接反了。

具体到置顶区（`/bookmark/query` 整棵树按 `LIST` 解析，见 `UserLayoutNodeServiceImpl:53`，`PinnedBookmarkGrid` 复用同一份数据）：

- 用的是 **FAVICON 优先**的选图（应该是 LOGO 优先）；
- 拿到的是**签在 64px** 的图，塞进 56px 的格子，2× 屏下需要 112px，**必糊**；
- **不走 monogram 兜底**——`build()` 里那个判断只在 `mode == TILE` 时生效，所以一张 16px 的 favicon 会被硬拉到 56px。

文案侧这件事**已经解决过一次**：`BookmarkShow.tileTitle` 就是为置顶区多算的一份 TILE 文案，`initDisplay` 的注释里明确写了它是「模式必须取同一个值」这条规则的唯一例外。图标侧没有对应的 `tileLogo`，**是遗漏不是设计**。

**采用方案 A：补一个 `tileLogo`，对称于 `tileTitle`。** 同时把书签库与分享页从 TILE 改回 LIST —— 它们渲染的是 20–28px 的小格子，本来就该走小图那一档。这两件事必须一起做：只补 `tileLogo` 而不纠正另外两处，等于把「模式与实际尺寸对不上」这个错误留在原地，只是不再是最显眼的那一处。

不扩 `DisplayMode` 为三档，理由是第三档的规则与 TILE 完全相同、只有像素数不同——把一个**像素参数**升格成一个**语义枚举值**不划算，而且改枚举要连带改两份 `enums.generated.ts`。`AssetRolePolicy` 的两档本来就不是尺寸档位，是「大图 vs 小图」两种相反的优先级。

代价可控：两次解析读的**是同一批行**，取数只做一次、纯函数跑两遍（这正好是 §4 里把「取数」和「判定」分层之后自然拿到的性质）。

**另有一个独立疑点要先实测：`m_fill` 会把宽 LOGO 裁成方的。** `OssUtils.signAsset` 对图标同时传 `w` 和 `h`，`resizeStyle` 因此走 `m_fill`（填充+裁剪）；而前端 `imageStyle` 用 `object-fit: contain`，其语义是「保持比例、不裁」。两边打架。`JSON_LD_ORG_LOGO` 恰恰经常是横向字标，一张 1024×256 的字标在 `m_fill,w_256,h_256` 下会被居中裁成 256×256（OSS 默认 `limit_1` 只挡放大，不挡这种缩小后的裁剪）。**先用真实 key 试一次 `x-oss-process` 确认**，再决定图标是否改用 `m_lfit`。

### 3.3 问题三：透明背景 + 图标紧贴边缘 —— **已做完（2026-08-17）**

此前记的「前端取色会被 CORS 挡住、所以做不了」不成立，这条纳入计划。

`BookmarkLogo.vue` 现在的渲染是：外层 `bg-gray-100` 圆角卡片 → 内层 `bg-white`（有主色时换成 `surfaceColor`）→ 图片 `width/height: 100%` + `object-fit: contain`。也就是说**图片恒等于铺满整个卡片，一个像素的留白都没有**。透明背景本身有白底/主色底接着，不难看；难看的是「铺满 + 图形本身就顶到边」这两件事叠在一起。

而算这件事需要的机械已经全在跑了：`decodeIconImage` + `sampleIconPixels` 每张图标都会执行一次，产出 32×32 的 RGBA `ImageData`，`resolveLogoSurfaceColor` 已经在用它算主色。**判断「图形有没有顶到边」只是对同一份 `ImageData` 多跑一次扫描**：取 alpha ≥ 阈值的像素的包围盒，边缘占比超过阈值就认为是满幅，给一档固定 padding。

**落点：前端**，与 `surfaceColor` 同一条链路上的同一次解码。

| 落点 | 结论 |
| --- | --- |
| **前端**（复用现成的 `ImageData`） | ✅ 采用。零契约改动、零重抓、对存量立即生效；缓存未命中时取不到像素，退回无 padding（与 `surfaceColor` 现在的行为一致） |
| **scrapper**（报事实） | 后续可选。只有它手里有原始字节，符合「scrapper 报事实、API 定策略」；但要改跨服务契约 + 三份契约测试，且**必须全量重抓**才对存量生效 |
| **API 的 `SiteAssetIngestor`** | ❌ **做不到**。API 从来没有图片字节，只收到 `width/height/mime/contentHash`。文档此前写「在 `SiteAssetIngestor` 落库时算好」是错的 |

实现上的几个已知点：

- **主色和 padding 必须共用一次解码**，因此 `logoColorCache` 要从「缓存一个颜色字符串」改成「缓存一个外观对象」（`{ surfaceColor, padding }`）。这是 §4 前端抽离要顺带做的。
- **`LOGO_SAMPLE_SIZE = 32` 可能需要上调。** 32px 下 1 像素边框就占 3%，包围盒判断的分辨率偏粗。改它同时影响主色统计（那边 32 是够的），所以要么两者共用一个更大的采样尺寸，要么分开采两次——**倾向共用 64**，多出来的一次 `getImageData` 是 16KB，可以接受。
- **判据要两个条件同时成立**：边缘有内容 **且** 背景透明。一张满幅不透明的方形图标（自带色块底的 PNG favicon）本来就该铺满，给它加 padding 会凭空缩小一圈。

**真·透明（透出桌面壁纸）不做。** 那需要 `bg-gray-100` 与 `bg-white` 两层写死的底色都改成字段驱动，而主色底（`surfaceColor`）在视觉上已经解决了「透明图标没有依托」的问题；真透明反而会让磁贴在深色壁纸上直接消失。

---

## 4. 解耦：把图标做成一个独立板块（已完成）

目标不是「新建几个文件」，而是让**改图标规则时需要读懂的代码量**降下来，并且让 §3.2 / §3.3 有地方落。下面每一步都是可独立提交、可独立回滚的。

### 4.1 现状：耦合面在哪

**后端 —— `SiteAssetResolver`（379 行）已经是个杂物间，7 个公开方法服务 5 件不相干的事：**

| 方法 | 实际在回答的问题 | 被谁用 |
| --- | --- | --- |
| `resolveBatch` / `resolveOne` | 这个书签的**图标**长什么样 | 5 处业务 |
| `resolveCoverBatch` / `resolveCoverOne` | 这一页的**封面**长什么样 | 详情面板 |
| `assetsOf` / `assetsOfBatch` | 这个书签**有哪些**原始资产 | 后台排查 |
| `objectsOf` | 一批 `file_id` 对应的**账本行** | `SiteServiceImpl:173`、`BookmarkAdminService:448` |
| `siteFaviconByHost` | 给一个 **host** 一张小图标 | scrapper 调用日志 |

根 `CLAUDE.md` 明写「`resolve` 选图标，`resolveCover` 选封面，两者不能混」，而代码里它们就在同一个类。`objectsOf` 更是纯取数工具，与图标毫无关系，却被两个外部类**伸手进图标解析器**去拿。

另外三处：

- **`ResolvedLogo` 是 `SiteAssetResolver` 的嵌套类**，于是 `Response.kt`（1112 行的 VO 总表）为了给一个字段命名，必须 `import` 一个 `@Service`。
- **「解析 + 注入」这套三步舞在 5 个调用点重复**（收集 pageId → `resolveBatch(ids, mode)` → `forEach { initDisplay(map[id], mode) }`），而 `initDisplay` 的 KDoc 自己记着：这两个参数做成必填，就是因为历史上有调用点忘了调，前端静默退化成色块。**重复 + 曾经漏过 = 应该合成一次调用。**
- **渲染尺寸有三份副本**：`SiteAssetResolver.renderSize`（私有）、`InternalAssetController.ALLOWED_SIZES`（靠注释 `64/256 = SiteAssetResolver.renderSize(LIST/TILE)` 同步）、nginx 的 `map` 白名单。三处一致靠人记。

**前端 —— `server/utils/index.ts` 共 470 行，其中 221–470 行（约 250 行）全是图标机械**：`iconCacheKey`、`resolveCachedIconBlob`（IndexedDB 缓存 + 并发合流）、`decodeIconImage`、`sampleIconPixels`、`dominantRgbOf`、`rgbToHsl`、`logoSurfaceColorOf`、`resolveLogoSurfaceColor`。它们和 `cn()` / `randomId()` / `externalHref()` / `isBookmarkableUrl()` / `md5` 同处一个 barrel。

两个具体后果：

- 这些函数被 Nuxt 自动导入进 **nitro（服务端）**（`.nuxt/types/nitro-imports.d.ts` 里能看到 `resolveLogoSurfaceColor`），而那边没有 `document`、没有 canvas。现在靠每个函数开头的 `if (!import.meta.client) return` 挨个自保。
- **消费者只有 1 个**（`BookmarkLogo.vue`）。250 行、单一消费者、且与 barrel 里其它东西零共享——这是能拆得最干净的一种情况。

`BookmarkLogo.vue`（226 行）自己也堆了 6 件事：尺寸档位、本地/IP 特判、失活蒙版、blob 缓存编排、主色计算、monogram 与 img error 兜底。

### 4.2 后端步骤

**① 把 `ResolvedLogo` 移出 `SiteAssetResolver`，单独成文件。**
纯类型搬家，零行为变化。做在最前面，是因为它是后面每一步的前置：只要结果类型还嵌在 `@Service` 里，任何想引用它的地方都被迫依赖那个服务。顺带改名（`ResolvedIcon`），与封面区分开。

**② 抽出取数层 `SiteAssetQuery`。**
把「按 pageId 批量取站点资产 + 页面资产」「按 host 取站点资产」「批量换账本行」三件事收进来，只返回实体和账本行，**不做任何判定、不签任何地址**。`objectsOf` 的两个外部调用方改为依赖它——它们本来要的就是这个，与图标无关。

**③ 按用途拆成两个 resolver。**
`IconResolver`（图标）与 `CoverResolver`（封面）各自依赖 ②，互不相识。文档里那条「两者不能混」从此由包结构表达，而不是靠一段注释。`assetsOf` / `assetsOfBatch` 是后台排查用的原始列表，归 ② 或直接归后台。

**④ 把「图标渲染规格」收成一处。**
mode → (边长, fit 模式) 做成一个显式表，`IconResolver` 与 `InternalAssetController.ALLOWED_SIZES` 都引用它，nginx 那份在注释里指过来。§3.2 的 `m_fill`/`m_lfit` 疑点正好落在这个表上——现在「用 fill 还是 lfit」是靠 `signAsset` 传不传 `height` 隐式表达的，不该是隐式的。

**⑤ 把「解析 + 注入」合成一次调用。**
提供 `IconResolver.decorate(shows, mode)`：调用方给一批 `BookmarkShow` 和一个 mode，拿回装饰好的。`initDisplay` 降为内部实现。收益是调用方不再需要知道 pageId→资产的映射，注入 `SiteAssetResolver` 的 6 个类里有 4 个可以不再注入它。

**⑥ 在 ⑤ 里实现 `tileLogo`（§3.2）。**
一次取数、纯函数跑两遍，产出 `logo` + `tileLogo`。**如果不先做 ⑤，这件事要在 5 个调用点各写一遍**——这是「先解耦再加功能」在这里的具体价值。

**⑦ 重算入口（§3.0）。**
挂在 ② 之上：读出存量行 → 跑 `assignRoles` → 回写 role/quality/isPrimary，按 `owner_id` 分批、幂等、有日志。归属不动，因此不触发 `replaceLayer`。

### 4.3 前端步骤

**① 把 221–470 行整体搬出 `server/utils/index.ts`，分三个文件。**
搬家阶段不改一行逻辑，`@utils` 继续 re-export 以免动 `BookmarkLogo.vue`（唯一消费者），确认 `pnpm typecheck` 干净后再收窄导出。

```
server/utils/icon/
├── cache.ts        # iconCacheKey + IndexedDB 字节缓存 + 并发合流
├── pixels.ts       # decodeIconImage + sampleIconPixels → 一份 ImageData
└── appearance.ts   # 由 ImageData 推出外观：surfaceColor + padding
```

`pixels.ts` 只负责「拿到像素」，`appearance.ts` 负责「像素说明什么」——**§3.3 就落在 appearance.ts**，与主色共用 `pixels.ts` 的那一次解码。

**② `appearance.ts` 的缓存从「一个颜色」改成「一个外观对象」。**
现在 `logoColorCache: Map<string, string | null>`，加了 padding 之后必须是 `Map<string, IconAppearance | null>`，否则两个值各缓存一份会各解一次图。这一步与 §3.3 是同一次改动。

**③ 抽 `useBookmarkIcon(value, size)` composable。**
把「这个书签该长什么样」的判断从 `BookmarkLogo.vue` 里摘出来，返回一个描述对象（走哪个分支 / 图片地址 / 底色 / padding / monogram 字符与色相）。组件只剩四个渲染分支（图片 / 圆点 / 色块 / 失活蒙版）。置顶区将来要读 `tileLogo`，改的是这个 composable 一处。

**④ `typing/` 里给图标相关类型单独归拢。**
`BookmarkLogo` 这个接口现在混在 `typing/bookmark.ts` 里。VO 仍是手抄的（不在 `generateSharedEnums` 覆盖范围内），单独成文件至少让「后端加了字段、这边要跟着抄」有一个明确的落点。

### 4.4 刻意不做的解耦

- **不把图标拆成独立微服务 / 独立 npm 包。** 只有一个消费者，包边界买不到任何东西，只会多一层版本同步。
- **不动 `AssetRolePolicy` 的文件位置。** 它已经是纯函数、已经有最厚的测试，是这块里唯一不需要解耦的部分。
- **不把 VO 契约改成生成的。** 那是整个仓库范围的话题（根 `CLAUDE.md` 里 ~45 / ~110 个手抄接口），不该由图标这一块来开这个头。

---

## 5. 执行顺序与验收

排序原则：**先拿到量效果的工具，再做零风险的读侧修复，再解耦，最后做需要新接缝的功能。**

| # | 事项 | 依赖 | 验收 |
| --- | --- | --- | --- |
| ~~0~~ | ~~后台「图标判定总览」页~~ **已完成 2026-08-17**，见下方 | 无 | 27 / 40 / 86 / 10，改进空间 31 |
| ~~1~~ | ~~§3.1 ① 修 `\|\|`（读侧，纯函数）~~ **已完成 2026-08-17**，见下方 | 无 | **27 → 67**，MONOGRAM_QUALITY 归 0 |
| ~~2~~ | ~~拆 `SiteAssetResolver`~~ **已完成** | 无 | 纯搬家，248/248 |
| ~~3~~ | ~~重算入口~~ **已完成** | 2 | `POST /admin/icon/recompute-verdict`，支持 `dryRun` |
| ~~4~~ | ~~§3.1 ②③ 修借用（写侧）~~ **已完成**，并顺带修出第 ④ 条 | 3 | **67 → 79**，改进空间归 0 |
| 5 | `TILE_MIN_SIZE` / 尺寸未知 两个参数复核 | 4 | 见下方「仍未做」 |
| ~~6~~ | ~~渲染规格收口~~ **已完成**；`m_fill` 实测**仍未做** | 2 | `AssetUrlSigner.ICON_SIZES` 成为唯一来源 |
| ~~7~~ | ~~`decorate` + `tileLogo` + 四处 mode 纠正~~ **已完成** | 6 | 置顶区拿 TILE，书签库/分享页回 LIST |
| ~~8~~ | ~~前端 §4.3 ① 搬家~~ **已完成** | — | `utils/icon/{cache,pixels,appearance}` |
| ~~9~~ | ~~前端 §4.3 ②③ + §3.3 padding~~ **已完成** | 8 | `useBookmarkIcon` + 透明顶边留白 |

**每一步的通用验收**：`./gradlew test` 通过、web / admin `pnpm typecheck` 的 `src/` 保持 0 错误、后台总览页复测。**纯搬家的步骤（2、8）要求各档数字一字不差**——阶段一就是这么验的，行为变化和结构变化混在一起会让两边都验不出来。

### 第 0 步的落地记录（2026-08-17）

后台「网站管理 › 图标判定总览」（`/website/icon-verdict`），两个接口：`POST /admin/icon/verdict-overview`（汇总）与 `POST /admin/icon/verdict-sites`（按站点下钻）。

**判定一律走 `AssetRolePolicy` 现算，没有第二份 SQL。** §1 那段 SQL 是渲染逻辑的手抄复刻，而复刻件会在规则改动后悄悄漂走、然后用一个错的数字证明改动有效 —— 这比没有这张表更糟。为此把分档提成了 `AssetRolePolicy.tileVerdict` 与 `qualifiesForTile` 两个纯函数，并加了三条测试把它们与 `resolve` / `shouldFallbackToMonogram` 的等价关系钉死（`AssetRolePolicyTest`，穷举而非举例，因为漂移恰恰发生在没被举例到的分支上）。后端测试 240/240。

**这一步立刻还了两笔债**，都是那段 SQL 藏起来的（见 §1 的两条订正）：10 个「有资产行但没有一张能渲染」的站点被 `WHERE error_msg IS NULL` 提前筛掉，分母从 163 缩成 153；以及 31 个「有合格候选」被整体记在了 86 那一档，实际是 12 / 19 分布。第二条直接改变了 §3.1 各条修复的收益归属 —— 这正是这个页面要防的那类错误，只不过第一次是它自己抓到的。

页面上刻意做了两件事：把「走色块」拆成 `MONOGRAM_QUALITY` / `MONOGRAM_SIZE` 两档（规则的问题 vs 数据的问题，合并就量不出效果），以及把「改进空间」单独立成一个带边框的数（它不是第五档，是横跨前几档的子集）。

### 第 1 步的落地记录（2026-08-17）

`shouldFallbackToMonogram` 去掉 `quality == DEGRADED ||` 那半个判据，只留尺寸；镜像函数 `qualifiesForTile` 同步。生产实测 **27 → 67（17% → 41%）**，`MONOGRAM_QUALITY` 归 0，与 §3.1 预测一字不差。

`MONOGRAM_QUALITY` 这一档**保留但恒为 0**，且判据刻意写成「够大却仍然退回色块」而不是「quality 是 DEGRADED」—— 前者问的是**还有没有尺寸之外的否决权**，后者是把当时唯一那个否决权的名字写死。它再次非零就说明有人加回了与「放大会不会糊」无关的判据。

**新放行的 40 张图查过构成，没有观感风险**：24 张 APPLE_TOUCH_ICON（128–512px）、11 张 LINK_ICON、4 张 MANIFEST_ICON、1 张 JSON_LD_ORG_LOGO。**一张 MS_TILE_IMAGE 都没有**（宽幅 Windows 磁贴图是最容易被 `m_fill` 裁毁的一类），非方形的只有 `www.52yzzy.com` 一张 860×829，长宽差 3.6%。

### 第 2–9 步的落地记录（2026-08-17）

一口气做完，三端全绿：后端 **248/248**、web `pnpm typecheck` 0 条 + `pnpm build` 通过、admin `src/` 0 条。

**后端结构**（§4.2 ①②③④⑤⑥⑦）：`SiteAssetResolver` 那个 379 行的杂物间拆成了

```
SiteAssetQuery      只取数，不判定、不签地址
AssetUrlSigner      只签地址 + 渲染尺寸的唯一来源（ICON_SIZES）
IconResolver        选图标
CoverResolver       选封面     ← 「两者不能混」从注释变成了类型
ResolvedIcon / DisplayIcons   结果契约，独立于任何 @Service
AssetVerdictRecomputeService  写侧规则的重算入口
```

三处顺带的收口：`objectsOf` 这个纯取数工具不再被两个外部类伸手进图标解析器去拿（改依赖 `SiteAssetQuery`）；`Response.kt` 不再为了给一个字段命名而 `import` 一个 `@Service`；`InternalAssetController.ALLOWED_SIZES` 直接引用 `AssetUrlSigner.ICON_SIZES`，不再手抄（nginx 那份仍是手抄的第三份，配置文件读不到 Kotlin 常量）。

**`initDisplay` 的签名从 `(resolved, mode)` 变成 `(DisplayIcons)`。** 从前「用 TILE 选图、用 LIST 选文案」这种自相矛盾的组合在类型上完全合法，只能靠一段注释拦着；现在两者出自同一次 `resolveForDisplay`，想写错也写不出来。配套的 `decorate(shows, mode)` 把此前在 5 个调用点各写一遍的三步舞收成一次调用。

**四处界面的 mode 全部纠正**（§3.2）：置顶区磁贴拿到了 `tileLogo`（LOGO 优先、256px、带 monogram 兜底），书签库与分享页从 TILE 改回 LIST。前端 `BookmarkLogo` 的 `variant` 默认跟着 `size` 走（M ⇒ tile），调用方只说自己多大，不必知道后端有两种展示模式。

**前端解耦 + padding**（§4.3、§3.3）：`server/utils/index.ts` 里那 250 行图标机械搬进 `utils/icon/{cache,pixels,appearance}`，判断逻辑再从组件里摘进 `useBookmarkIcon`。§3.3 的留白判定落在 `appearance.ts`，与主色**共用同一次像素解码**（那是这条链路上唯一真正花时间的一步），缓存也随之从「一个颜色字符串」改成「一个 `IconAppearance`」。采样尺寸 32 → 64：32px 下一圈 1px 边框就占 3%，包围盒判定太粗。

### 第 4 步顺带修出的第 ④ 条缺陷

修完 ②③ 后用生产数据模拟，发现仍有 6 个站点够不着库里的大图。查下来是**同一个错误的第三次出现** —— 用途/可信度被当成绝对闸门，压过了「够不够大」这个真正的渲染要求：

- `roleOrder` 的循环一旦在 LOGO 层拿到候选就 `return`，**永不下探 FAVICON**。于是 `gitlab.com` 一张尺寸未知的 JSON-LD logo 挡住了两张 192px 的 favicon，`element.eleme.cn` / `www.chiphell.com` 的小 LOGO 挡住了矢量 mask-icon。
- 同一层内部也一样：`hellogithub.com` 的 LOGO 层里 `compareBy{quality}.thenBy{size}` 让 **TRUSTED 48px 压过了 DEGRADED 192px**。

修法是在 TILE 模式下先按 `qualifiesForTile` 筛一道，**role 与 quality 只在撑得起大图的候选之间做偏好**；一张都不够大时退回原池（反正无论选谁都会走色块，让 role 偏好继续生效）。LIST 不受影响 —— 小图场景本来就该要小图。

这与 §3.1 ① 删掉的那个 `||` 是同一句话：**出处/用途是偏好，尺寸是硬要求。** 把偏好放在硬要求前面，结果就是宁可退成首字母色块也不用旁边那张大图。

### 最终数字：27 → 79，改进空间归 0

| | IMAGE | MONOGRAM_SIZE | NO_ASSET | 改进空间 |
| --- | --- | --- | --- | --- |
| 起点 | 27（17%） | 86 | 10 | 31 |
| 修完 ① | 67（41%） | 86 | 10 | 12 |
| 修完 ②③④ | **79（48%）** | 74 | 10 | **0** |

**正好命中 §3.1 算出的天花板。** 改进空间归 0 的含义是明确的：剩下 84 个站点库里**没有任何一张够大的图**，选图规则这条路已经走到头了 —— 再要提升只能从抓取侧多拿到几张图（§3.4 之外的话题）。

### 仍未做

- **第 5 步：`TILE_MIN_SIZE` 与「尺寸未知」两个参数的复核。** 现在才有干净的基线可以据此调整。注意 `gitlab.com` 那张 `JSON_LD_ORG_LOGO` 仍然栽在 `effectiveSize()` 对 null 返回 0 上。
- **`m_fill` 实测。** `AssetUrlSigner` 已经把渲染尺寸收成唯一来源，但「图标该不该改用 `m_lfit`」还没拿真实 key 试过 `x-oss-process`。当前放行的图里非方形只有一张（长宽差 3.6%），风险很低，但 `JSON_LD_ORG_LOGO` 这类横向字标将来会撞上。
- **重算 + 上线后的人眼复核。** 下面这条尤其重要。

### ⚠️ 上线顺序

写侧规则（②③④中的②③）改的是 `assignRoles`，**存量数据要跑一次重算才生效**：

1. 部署 API；
2. `POST /admin/icon/recompute-verdict?dryRun=true` 空跑，确认变更量；
3. 去掉 `dryRun` 实跑；
4. 打开「图标判定总览」复测，应为 **79 / 0 / 74 / 10，改进空间 0**；
5. **人眼看一遍置顶区。** 这一轮放行了 52 张此前不显示的图，而「好不好看」没有任何技术指标 —— 与截图那个「只截出裸 HTML」的老 bug 同理（见根 `CLAUDE.md`）。

### 仍然暂缓

**用户级图标覆盖。** 若做：键用 `content_hash`（理由见 §0），且它的第一价值是**产出带标注的分歧样本**（后台一张「用户选择 vs 规则选择」对照表），而不是每站点一个覆盖值。等 §3.1 修完、三档数字稳定下来再重新评估——很可能那时候剩下的分歧已经不值得做这件事。
