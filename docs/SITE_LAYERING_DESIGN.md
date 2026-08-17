# 站点 / 页面 / 用户 三层分离设计

> 设计日期：2026-07-31 · 范围：`bookmark`、`bookmark_user_link`、`site_asset`、`site_page_meta`、`site_display_pref` 及其上的抓取、巡检、展示链路
> 结论：`bookmark` 这一张表同时扮演着「站点」和「页面」两个角色，`bookmark_user_link` 里又混着抓取快照。**带路径/参数的深链（YouTube 视频、GitHub 仓库、Notion 页面）在当前模型下是错的，不是"旧"。**

> ⚠️ **本文用的是 2026-08-03 三层正名之前的表名。** 这是一份历史设计文档，描述的是当时的
> 库结构与当时的问题，把里面的名字改成新的只会让论述自相矛盾（比如下面说「`bookmark` 这张表
> 同时扮演站点和页面两个角色」——那正是它当年叫 `bookmark` 的原因）。读的时候按这张表换算：
>
> | 本文写的 | 现在叫 |
> |---|---|
> | `bookmark`（页面） | `page` |
> | `bookmark_user_link` / user_link（用户收藏） | `bookmark` |
> | `bookmark_ping_log` | `page_ping_log` |
> | `bookmark_category` | `page_category` |
> | `bookmark_sweep_log` | `sweep_log` |
> | `site_page_meta` | `page_meta` |
> | `bookmark_id`（指向页面的列） | `page_id` |
> | `BookmarkEntity` / `BookmarkUserLink` | `PageEntity` / `BookmarkEntity` |
>
> 正名的动机与执行见 `deploy/migrations/2026-08-03_rename_three_layers.sql`。

> ⚠️ **`site_display_pref` 已于 2026-08-17 整表移除**，本文对它的每一处论述（§5 的 owner 泛化、
> §8 的迁移顺序、§9 的溯源列、§10 的建表 bug）都只在描述历史，不再对应库里的任何东西。
> 移除的不是这张表的设计，而是它背后那件事：管理员逐站点微调图标（内边距 / 背景色 / 钉图）。
> 详见文末 **§11 后记**。

代码中多处注释写了"详见根目录 SITE_LAYERING_DESIGN.md"，指的就是本文件。

## 1. 触发这次设计的具体故障

用户收藏 `https://www.youtube.com/watch?v=A`，接着另一个用户收藏 `?v=B`：

| 环节 | 当前实际行为 | 位置 |
|---|---|---|
| canonical key | `(url_host, url_path)`，**query 被整个丢掉** → 两个视频收敛成同一条 `bookmark` | `BookmarkServiceImpl.getOrCreateByUrl` |
| `urlQuery` 的下场 | 解析出来了，但全项目只用于 `log.debug` | `WebsiteParser.kt:69` |
| 抓取目标 | `rawUrl` = `scheme://host + path` = `https://www.youtube.com/watch` —— **不是任何一个视频** | `BookmarkEntity.kt:72` |
| 第二个用户 | 24h 内 `checkFlag()` 返回 false，直接复用上一次抓取结果 | `BookmarkEntity.checkFlag` |
| 用户自己写的标题 | `title = appName ?: title ?: urlHost`，`appName` 来自 bookmark（YouTube 的 `manifest.short_name` = "YouTube"）→ **用户标题被站点简称顶掉** | `Response.kt:76` |

所以即使今天手动把视频标题写进 `bookmark_user_link.title`，前台照样显示 "YouTube"。

被否决的第一直觉方案：**把页面标题放进 `bookmark_user_link`**（"bookmark 存 youtube，user_link 存视频标题"）。
视频标题不是用户级事实，是页面级事实 —— 一万个人收藏同一个视频，标题都一样。放进 user_link 的后果是：
整条抓取/巡检/资产链路都以 `bookmark_id` 为主体（`site_page_meta`、`site_asset`、`next_check_at` 全是
`bookmark_id` 主键），`bookmark_user_link` 从来不被抓取，**这个标题没有任何人会去填**；除非再造一套
以 user_link 为主体的抓取路径，而"两套解析各写一份"正是 2026-07-29 那轮重构刚消灭的东西。

## 2. 四层模型

```
site          一个域名一行        youtube.com   → 品牌名/短名/favicon/logo/NSFW/域名活性
 └─ page       一个规范化URL一行   /watch?v=A    → 页面标题/描述/og图/页面活性
     └─ user_link   一个用户收藏一行   我的备注/原始URL/置顶/openCount
         └─ layout_node（已有，不动）
```

判断一个字段该放哪一层，只用一个问题：**换一个用户 / 换同域下另一个页面，这个值会变吗？**

| 字段 | 换用户会变 | 换页面会变 | 归属 |
|---|---|---|---|
| favicon / logo / 品牌名 / 短名 | ✗ | ✗ | **site** |
| NSFW / 域名是否可达 | ✗ | ✗ | **site** |
| 页面标题 / 描述 / og:image / 截图 | ✗ | ✓ | **page** |
| 我改的标题 / 备注 / 置顶 / 打开次数 / 原始 URL | ✓ | ✓ | **user_link** |

按这把尺子量，现在有三处放错了：

| 放错的字段 | 现状 | 应归 |
|---|---|---|
| `bookmark.app_name` | 站点短名，按页面存 | site |
| `bookmark.nsfw` / `nsfw_reason` | 站点属性，按页面判定 —— 同域每个页面各调一次 DeepSeek | site |
| `bookmark_user_link.title` / `description` | 创建时从 `bookmark` 拷来的**快照**，冒充用户数据 | page（用户没改过时） |

`site_page_meta` 里的 `site_name` / `site_short_name` 也是按页面存的站点级字段 —— 上一轮重构已经把这两个
字段识别出来了，只是当时没有 site 层可以安放。

## 3. `site`：域名级事实

```sql
CREATE TABLE site (
    id               varchar(40) PRIMARY KEY,
    host             varchar(200) NOT NULL,          -- youtube.com（含端口）
    scheme           varchar(10)  NOT NULL,
    link_type        varchar(20)  NOT NULL,          -- DOMAIN/LOCAL/IP/OTHER

    brand_name       varchar(200),                   -- og:site_name / manifest.name
    short_name       varchar(100),                   -- manifest.short_name（磁贴文案）
    nsfw             boolean NOT NULL DEFAULT false,
    nsfw_reason      varchar(50),

    is_alive         boolean NOT NULL DEFAULT true,  -- 域名级活性
    last_check_at    timestamp,
    next_check_at    timestamp,
    consecutive_fail integer NOT NULL DEFAULT 0,

    verify_flag      boolean NOT NULL DEFAULT false, -- 人工认证：品牌名/图标已核对
    locked_fields    varchar(200),
    create_time      timestamp NOT NULL DEFAULT now(),
    update_time      timestamp NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX uk_site_host ON site (host);
```

**品牌名/短名的权威值只从首页抓取写入。** 深链页面也会返回 `og:site_name`，但那是二等来源 —— 只在
`brand_name IS NULL` 时回填，不覆盖首页抓来的值。否则某个视频页写了个奇怪的 `og:site_name`，
整站品牌名就被它带跑了。

这一层带来的成本变化是决定性的。1000 个 YouTube 视频：

| | 现在 | 分层后 |
|---|---|---|
| favicon 抓取 + OSS 上传 | 1000 次 | 1 次 |
| DeepSeek NSFW 判定 | 1000 次 | 1 次 |
| 域名 ping | 1000 次 | 1 次 |
| 管理员手调图标 padding/背景色 | 1000 次 | 1 次 |

## 4. `bookmark` 收缩成纯页面级

```sql
ALTER TABLE bookmark
    ADD COLUMN site_id      varchar(40) NOT NULL,
    ADD COLUMN url_query    varchar(1000) NOT NULL DEFAULT '',  -- 规范化后的 query
    ADD COLUMN url_fragment varchar(500)  NOT NULL DEFAULT '';  -- 仅路由型 fragment

CREATE UNIQUE INDEX uk_bookmark_canonical
    ON bookmark (site_id, url_path, url_query, url_fragment);
```

**`url_fragment` 单独成列，不折进 `url_path`。** `https://a.com/app?x=1#/docs?y=2` 这种同时带真
query 和 hash query 的地址，折进去之后拼不回正确顺序（`?` 必须在 `#` 之前，hash 内部的 `?` 又必须
在 `#` 之后）。分列存，`rawUrl` 的拼接就是无歧义的 —— 见
`UrlCanonicalizer.CanonicalParts.rawUrl`。三列都用 `''` 而非 `NULL` 表示"没有"，查询不必处理
两种空值。

`url_host` **刻意保留**作为冗余列：搜索、后台列表、`findListByHost` 都靠它，为一次 host 过滤去 join
site 不值得。它由 site 单向同步，业务代码只读。

### 4.1 URL 规范化（`UrlCanonicalizer`）

整个设计里最容易踩坑的地方，收进一个纯函数类：

1. **fragment 默认丢弃，但 `#/` 与 `#!` 开头的（路由型）进 key。** hash 路由的 SPA 丢了 fragment 会
   让整站 collapse 成一行；反过来 `#comments`、`#L42` 这类页内锚点指向同一份文档，不丢掉就会让
   同一页面的每个锚点各占一条记录、各抓一次。
2. **追踪参数必须用黑名单剥离，绝不能用白名单**（`utm_*` / `fbclid` / `gclid` / `spm` / `ref` /
   `ref_src` / `share_source` / `from` …）。`?v=xxx`、`?id=123` 这类业务参数不可枚举，白名单会把它们
   一并剥掉，指向另一个页面。
3. **剩余参数按 key 排序。** 同一视频从不同渠道分享，参数顺序不同，不该产生两行。
4. path 末尾斜杠归一（沿用现有逻辑）。

> **规范化只服务于「去重 + 抓取目标」，绝不改变用户点击的目标。**
> 用户点击永远走 `bookmark_user_link.url_full`（原始 URL，带全部参数）。有的链接去掉未知参数就打不开。
> 以后若有人为了"统一"把点击目标也换成规范化 URL，那就是引入一个只在部分站点上复现的 bug。

### 4.2 两级活性

深链的腐烂速度远高于域名（视频被删、仓库归档），但域名死了就没必要逐页检查：

```
site 巡检（低频、便宜）：HEAD 根域名 → site.is_alive
  ├─ site 活着 → page 巡检：HEAD 具体 URL → bookmark.is_activity
  └─ site 死了 → 该 site 下全部 page 直接标记不可达，一次都不查
```

**页面级活性沿用现有的 `bookmark.is_activity`，不新增 `page_alive` 列。** `bookmark` 一行本来就是
一个页面，`is_activity` 已经是页面级的；这次分层真正缺的只有站点级那一份 `site.is_alive`。
再加一列同义的 `page_alive` 只会得到两列永远要同步写的重复状态。

深链的失败退避比首页激进得多：视频删除是永久的，重试 20 次也不会回来。

## 5. `site_asset` / `site_display_pref` 的 owner 泛化

```sql
ALTER TABLE site_asset
    ADD COLUMN owner_type varchar(10) NOT NULL DEFAULT 'PAGE',  -- SITE | PAGE
    ADD COLUMN owner_id   varchar(64);
```

现有的 `AssetRole` 已经天然沿这条线分开了，不需要新造概念：

| role | 归属 | 理由 |
|---|---|---|
| `FAVICON` / `LOGO` | **SITE** | 全站一套，同域所有页面共享 |
| `SOCIAL`（og:image）/ `SCREENSHOT` | **PAGE** | 每页不同，就是页面内容本身 |

`site_display_pref` 直接改成挂 site（`site_id + display_mode`）—— 人工调 padding / 背景色 / pinned
asset 只对站点图标有意义。

**scrapper 契约完全不用改。** 它照旧返回 `siteName` / `shortName` / `icons` / `ogImage`，只是 API 侧把
这些事实分流到不同的层。这正是 `docs/oss-architecture.md` 那条规则的自然延续：**scrapper 报事实，
分层是 API 的策略。**

## 6. `bookmark_user_link` 只剩用户私有数据

`title` / `description` 默认 `NULL`，**只在用户真的编辑过时才写值**。`NULL` 自身就是"没改过"的标记，
不需要额外的 `title_source` 列 —— 覆盖策略随之变得显然：`NULL` 用抓取值，非 `NULL` 是用户的，永不覆盖。

现在 `BookmarkUserLink` 的两个构造函数（`BookmarkEntity.kt:166`、`181`）在创建时就拷贝了
`bookmark.title` / `description`，于是"用户手改的标题"和"创建时拷来的快照"在数据上不可区分，
永远判断不出该不该覆盖。删掉这两行拷贝即可。

## 7. 展示策略（`BookmarkDisplayPolicy`）

新建一个与 `AssetRolePolicy` 同构的纯函数类：无 IO、可离线测试、是重点测试对象。

这里有一个必须正面回答的问题：

> 一屏 20 个 YouTube 视频磁贴，如果文案都用 `short_name`，全都叫 "YouTube"，用户没法区分。

所以**磁贴文案要按「首页 / 深链」分叉**，而不是无条件用短名：

| | TILE（大图 + 短文案） | LIST（小图 + 全名） |
|---|---|---|
| 首页（`path='/'` 且无 query） | `user.title` → `site.short_name` → `site.brand_name` → host | `user.title` → `page.title` → `brand_name` → host |
| 深链 | `user.title` → **`page.title`（截断）** → `short_name` → host | `user.title` → `page.title` → host |
| 图标 | site `LOGO` → site `FAVICON` → 首字母块 | site `FAVICON` → site `LOGO` → 首字母块 |

理由是一句话：**跨站点靠图标区分，同站点内靠文案区分。** 深链之间图标完全一样（都是 YouTube 图标），
文案就必须是页面级的。

`Response.kt:76` 现在的优先级是反的（站点短名压过用户标题），随这一步一并修正。

## 8. 迁移路径

按项目惯例：迁移手工执行（`deploy/migrations/`，无 Flyway），部署流程**不会**自动跑。

| 步骤 | 内容 | 风险 |
|---|---|---|
| 1 | `2026-07-31_site_table.sql`：建 `site`，从 `bookmark` 按 host 去重回填；`bookmark.site_id` 回填 | 纯加列，旧代码不受影响 |
| 2 | `2026-07-31_bookmark_page_key.sql`：加 `url_query` / `url_fragment` / `page_alive`，**删掉旧的 `(url_host, url_path)` 唯一约束**，建新唯一索引 | 见下 |
| 3 | 代码切到新模型（`UrlCanonicalizer`、`SiteService`、两级巡检、`BookmarkDisplayPolicy`）；`site_asset` 加 owner 列并按 role 迁移 | 主要工作量 |
| 4 | 跑**拆分修复任务**：遍历 `bookmark_user_link.url_full` 重新规范化并重新绑定 `bookmark_id`，把被合并的深链拆成多行 | 只能用代码做，见下 |
| 5 | **全量重抓** | 抓取量 = 拆分后的 bookmark 行数 |
| 6 | 清理 `bookmark.app_name` / `nsfw` / `nsfw_reason`，`site_id` 收紧为 `NOT NULL`，抹掉 user_link 的快照标题 | 确认无回滚需求后再执行 |

**第 6 步的进度（2026-08-03）**：`nsfw` / `nsfw_reason` 删列 + `site_id` 收紧 NOT NULL 已落到
`2026-08-03_site_layering_cleanup.sql`（**必须部署后执行**，方向与前几个迁移相反）；user_link
快照标题早已抹净（生产库 8 行全为 NULL）。只剩 `app_name` 没做，理由见 §9 —— 它不是遗留列。

两点容易判断错的地方：

- **第 2 步的唯一索引不会冲突，不需要预先去重。** 存量行的 `url_query` / `url_fragment` 全部回填为
  `''`，而 `(url_host, url_path)` 本来就唯一、`site_id` 与 `url_host` 又一一对应，所以新索引在存量
  数据上必然成立。但**旧唯一约束必须删掉**，否则 `(host, /watch)` 仍然唯一，`v=A` 与 `v=B` 永远拆不开。
  该约束的索引名当年没进版本库，迁移里用「唯一 + 恰好这两列」反查后动态删除。
- **第 4 步不能用 SQL 做。** 被丢掉的 query 在 `bookmark` 里已经不存在了，唯一还留着完整地址的地方是
  `bookmark_user_link.url_full`。所以拆分必须走代码：对每条 user_link 重新跑
  `UrlCanonicalizer` → `getOrCreateByUrl` → 重绑 `bookmark_id`。第 5 步同样不可跳过：所有带 query
  的深链，抓的都是剥掉 query 后的另一个页面，库里那批标题不是"旧"，是**错**。

## 9. 落地进度与执行顺序

迁移是手工执行的（`deploy/migrations/`，无 Flyway），**顺序不能乱**：

| 顺序 | 迁移文件 | 相对部署的时机 |
|---|---|---|
| 1 | `2026-07-31_site_table.sql` | 部署**前** |
| 2 | `2026-07-31_bookmark_page_key.sql` | 部署**前** |
| 3 | `2026-07-31_site_asset_owner.sql` | 部署**前**（只加列/回填/去重/索引） |
| — | **部署新版 API** | |
| 4 | `2026-07-31_site_asset_owner_not_null.sql` | 部署**后**（收紧约束） |
| 5 | `2026-07-31_user_link_title_snapshot.sql` | 部署**后**（理由见文件头） |

**为什么第 3 步要拆成 3 与 4 两个文件**：那批 `NOT NULL` 约束的两侧都会炸 ——

- 部署**前**就收紧 → 线上仍是旧代码，它 INSERT `site_asset` 不带 `owner_type`/`owner_id`、
  INSERT `site_display_pref` 不带 `site_id`，于是**每一次抓取都会违约失败**；
- 等部署**后**再整块跑 → 部署完到迁移之间，新代码要写 `owner_type` 而列还不存在，同样每次抓取都失败。

所以「加列 + 回填 + 去重 + 索引」放部署前（旧新代码都能容忍），「收紧约束」放部署后（此时只有
新代码在写）。中间那段过渡期两边都能跑，代价是旧代码这期间插入的行 `site_id`/`owner_*` 为空，
由第 4 步的兜底回填收拾。`bookmark.site_id` 的 `NOT NULL` 同理留到第 4 步。

代码已全部落地：`UrlCanonicalizer`、`SiteEntity`/`SiteService`、canonical 四元组、
`AssetOwnerType` 分层、`BookmarkDisplayPolicy`、user_link 快照清理、NSFW 判定上移、
`DeepLinkSplitRepair`、两级巡检短路（`LivenessPolicy.siteVerdict`）。

### 第 4 步的拆分修复怎么跑

`POST /admin/website/repair-deep-link-split?dryRun=true` → 看 `repointed` 的量级 →
再用 `dryRun=false` 真正执行。幂等，可重复调用、可中断后重跑。

返回值里 `awaitingCrawl` 是待抓页面数：修复**刻意不触发抓取**，拆出来的 `PENDING` 记录交给
`checkAll()`（5 分钟一轮）按自己的节奏喂解析池 —— 一次性 publish 几千个事件会打满解析池及其
有界队列，之后 `CallerRunsPolicy` 会把剩下的抓取直接跑在 Tomcat 请求线程上，批量导入当初就是
因为这个才改成不发事件的。所以第 5 步的「全量重抓」不需要额外动作，它是第 4 步的自然结果。

`orphanedBookmarks` 是拆完后不再被任何用户链接引用的旧记录数，**只统计不删**：它们还挂着
`site_page_meta` / `site_asset` / `scrape_snapshot`，级联删除的边界值得单独一轮处理。

### 两级巡检的安全底线

判活与判死**不对称**，这是这块唯一需要小心的地方：

| 方向 | 需要根地址确认？ | 理由 |
|---|---|---|
| 判活 | 不需要 | 任意一个页面通了，域名必然活着（零额外探测） |
| 判死 | **必须** | 「所有候选页面都失联」不足以判定域名死亡 |

用户收藏的大多是深链，而深链失效（视频被删、仓库归档）与域名死活无关。若一个被删的视频就能把
`youtube.com` 判死，下一轮该域名下**所有**页面都会被站点层短路成失联、不再实际探测 ——
一次误判级联成整站误判，且再没有探测能纠正它。与 `LivenessPolicy.breakerReason` 防的是同一类
事故：不要让局部证据推出全局结论。

规则本身是纯函数 `LivenessPolicy.siteVerdict(pageOutcomes, rootOutcome)`，由
`LivenessPolicyTest` 钉住每个分支。另外两处配套约束：

- **熔断只看真正探测过的结果。** 短路出来的 DEAD 是上一轮结论的复用，不是探测结论；混进去会
  凭空拉高失联比例，让「>90% DEAD」这条规则在健康系统里误触发。
- **`bookmark_ping_log` 只记真正探测过的页面。** 这张表的语义是「一次探测一行」，把短路的写进去
  会让基于它的失联率统计全部失真。

`site.next_check_at` 刻意留空：目前没有独立的站点级巡检调度器，域名活性是页面巡检的副产物。
留 NULL 而不是填一个没人读的值，免得以后真加调度器时被陈旧游标误导。

### 过渡期的「读两层」——已收敛（2026-08-03）

原先有两处刻意的双读，等写入端上移后再收敛。现状：

| 位置 | 结果 |
|---|---|
| `bookmark.nsfw` | ✅ **已收敛**。写入端其实早就上移到了 `siteService.markNsfw`，只是 mapper 注释一直写着「写入端还在 bookmark 上」没跟着改，读路径就一直维持双读。现已统一为 `COALESCE(st.nsfw, false)`（3 处 mapper SQL + `BookmarkShow`），列由 `2026-08-03_site_layering_cleanup.sql` 删除。执行前实测生产库 `bookmark.nsfw=true` 有 0 行，无判定丢失。 |
| `bookmark.app_name` | ❌ **未收敛，且比文档描述的严重得多**。见下。 |

收敛 `bookmark.nsfw` 时有一个不显然的陷阱：`BookmarkAdminVO` 是 `BeanUtil.copyProperties`
整体拷贝出来的，删列之后那次拷贝什么也拷不到，`vo.nsfw` 会**静默**停在默认的 `false` ——
后台从此再也标不出违规站点，且没有任何报错。现已改为在 `adminListAll` / `adminDetail`
两处按 `siteId` 显式回填（列表走 `siteService.mapByIds` 批量，避免 N+1）。

### `bookmark.app_name` 并不是「新代码不读」

上面那行「保留但新代码不读」是**写错了**，照它去删列会直接删坏功能。实际仍在使用的地方：

| 端 | 位置 |
|---|---|
| 写 | `BookmarkEntity.successInit`、`inferAndSetAppName`（DeepSeek 简称推断）、`parseLocally` |
| 读 | 管理后台搜索过滤（`BookmarkSearchParams` / `BookmarkServiceImpl:143`）、`BookmarkAdminVO` |
| 机制 | 字段锁 `BookmarkLockedField.APP_NAME`、管理后台的 `adminGenerateAppName` |
| 前端 | `pages/index.vue` 与 `AddOneDialog.vue` 的 `item.title \|\| item.appName \|\| item.urlHost` 兜底 |

所以删 `app_name` 不是清理批次里的一行 `DROP COLUMN`，而是一次跨 API / 管理后台 / 前端的
功能迁移：要把 DeepSeek 简称推断的写入端改写到 `site.short_name`、把后台的搜索与编辑迁过去、
把字段锁从 `BookmarkLockedField` 挪到 `SiteLockedField`、再改掉前端两处渲染兜底。
**单独立一批做，别混进 schema 清理。**

### 两个溯源列刻意保留

`site_asset.bookmark_id` 与 `site_display_pref.bookmark_id` 看着像分层没做干净，其实不是：
`owner_type`/`owner_id` 回答的是「归属谁」，`bookmark_id` 回答的是「当初从哪个页面抓到 /
在哪个页面上调的」。这是两个不同的事实，不是重复的第二套真相，删掉是净损失。

## 10. 顺手修掉的既有 bug

- `BookmarkServiceImpl.kt:108` `findByHost()` 用 `.one()` 查 host —— 同一 host 有两个路径就抛异常
  （现在已经可能发生）。它的真实语义是"查 site"，新模型下变成 `site` 表的唯一键查询。
- `WebsiteParser.kt:69` 解析出的 `urlQuery` 全项目只用于打日志，现在终于有归宿。
- `Response.kt:76` 用户自定义标题被站点简称覆盖（`appName ?: title` 优先级写反）。
- **`site_display_pref` 建表时没有 `id` 列**，而 `SiteDisplayPrefEntity` 标了 `@TableId var id`
  —— MyBatis-Plus 的 `insert` 会带上一个不存在的列，「首次为某书签保存展示偏好」这条路径从来
  没成功过（已有行走 `update` 分支，所以只在新行上暴露）。`2026-07-31_site_asset_owner.sql`
  把列补上了。

## 11. 后记：`site_display_pref` 于 2026-08-17 移除

> 追记日期：2026-08-17 · 迁移：`deploy/migrations/2026-08-17_drop_site_display_pref.sql`

上文 §5 把 `site_display_pref` 从挂 bookmark 改成挂 site，理由是「人工调 padding / 背景色 /
pinned asset 只对站点图标有意义」。那个判断本身没错 —— 键确实该是站点。**错的是上一层：
这件事根本不该由人来逐站点做。**

三条结论，留给将来想重做这个功能的人：

1. **覆盖面差一个量级，且差距永不消失。** 规则（`AssetRolePolicy`）是纯函数，改一次同时作用于
   全部站点**以及所有还没被收藏的站点**；人工设置只覆盖被调过的那一个，而用户对图标质量的感受
   由「刚添加的那一条」决定，那一条恒为未调过。这不是冷启动问题，是结构性的。
2. **这张表在生产上一行都没有。** 从 2026-07-31 上线到删除，`site_display_pref` 总行数 0
   —— 调过内边距 0 / 调过背景色 0 / 钉过图 0。它不是「用得少」，是从来没被用过。
3. **钉图那一列的标的本来就不稳定。** `SiteAssetWriter.replaceLayer` 在资产集合有任何变化时
   整层删除重插，新行是新 id；而读取侧是 `usable.firstOrNull { it.id == pinned }`，匹配不上就
   静默回落自动排序，没有报错也没有日志。**将来真要做用户级图标覆盖，键必须是 `content_hash`**
   —— `AssetRolePolicy.divergesFromSite` 已经在用字节哈希做跨抓取比对，那是现成的稳定身份。

同批移除的还有 `POST /admin/bookmark/{pageId}/icon`。它捎带的 `appName` **不属于图标**：那是
TILE 磁贴标题的主要来源（生产 92 个首页里 75 个靠它出标题，`site.short_name` 只有 15 个），
所以随端点一起搬进了 `BookmarkBasicInfoUpdateParams` / `adminUpdateBasicInfo`，连同
`PageLockedField.APP_NAME` 的加锁/解锁逻辑。漏掉这一步的代价是「后台再也改不了书签简称，
而且不会报错」。

后台的「显示设置」卡片只砍掉写侧，读侧改名为「渲染结果」保留：它的 `previewUrl` / `monogram`
从来就不来自这张表，而是 `SiteAssetResolver` 现算的 —— 它回答「这个书签在两种模式下实际会
渲染成什么」，正是接下来排图标规则时最需要的那个事实。

剩下的工作（`shouldFallbackToMonogram` 把出处判断与渲染判断用 `||` 连在一起、TILE 模式下的
借用机制是净负收益、借用取的是第一张而非最大的一张）记在根目录 `ICON-DISPLAY-TODO.md` §3。
