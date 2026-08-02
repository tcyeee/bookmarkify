# 文件系统重构计划

> 起草日期：2026-07-31 · 范围：`bookmarkify-api` 全部 OSS 对象的记账、去重与回收，附带 `bookmarkify-scrapper` 的 key 布局
> 前置阅读：[`docs/oss-architecture.md`](docs/oss-architecture.md)（2026-07-29 的 OSS 评审），本文是其中 §7 延后项 #10 与 §8 方案 A 的落地计划

## 1. 现状

桶里有四类对象，分散记在两本账上：

| OSS 前缀 | 内容 | 记在哪 | 删除路径 |
|---|---|---|---|
| `bookmarkify/avatar/**` | 用户头像 | `user_file` | 换头像时删旧的 ✅ |
| `bookmarkify/bac/**` | 用户背景图 + 系统默认背景 | `user_file`（系统默认那批走 `UserFile(fileName)` 构造，**库里没有对应行**） | 用户删图时删 ✅ |
| `scrapper/{logo,og,...}/**` | 抓取的图标 / 社交图 | `site_asset.storage_url` | 重抓时按引用计数回收 ✅ |
| `scrapper/screenshots/**` | 页面截图 | `site_asset`（`SCREENSHOT` role） | 同上 |

`SiteAssetWriter.scheduleOrphanCleanup` 已经实现了跨书签引用计数、只删裸 key、挂 `afterCommit` 三条安全边界——**领域内的**生命周期管理是到位的，本次重构不是去补一个从零开始的空白。

### 1.1 真正缺的三件事

**（a）没有任何地方能回答"桶里到底有什么"。**
账分散在两张表，而且 scrapper 是**先 PUT 对象、后由 API 落行**。中间任何一步失败（`persist` 事务回滚、抓取超时、`fillMissingAssets` 撞唯一索引导致整个事务回滚），对象已经在桶里，库里却没有任何一行知道它存在。这类孤儿目前唯一的兜底是 `oss-architecture.md` §6.2 那条 lifecycle 规则，但它按前缀**无差别过期**，兜的是"全部"，不是"孤儿"。

**（b）注销账号不删文件。**
`UserServiceImpl.del()` 只把用户软删 + 清空身份列，头像和背景图的 `user_file` 行与 OSS 对象原样保留，且 key 可推导。这与 `oss-architecture.md` §2 自己定的规矩（"用户上传的文件必须随 DB 行同步删除"）直接冲突——换头像那条路径覆盖了，注销这条没有。

**（c）存储层无去重，且路径靠推导。**
key 是 `SHA256(源URL)`，同一张图挂在不同 URL 下就存两份；`content_hash` 明明已经算出来了却只用于 `AssetRolePolicy` 的降级判定，没用于存储层。同时 `UserFile.fullPath` 由 `FileType.folder` 枚举**推导**而非存储，改一次目录布局，全部存量行的路径就断了。

## 2. 目标与非目标

**目标**

1. 全系统 OSS 对象收敛到唯一账本，能盘点、能对账、能回收
2. 引用方存 `file_id`，与存储层 key 布局解耦
3. 相同字节只存一份
4. 补齐（b）这个合规洞

**非目标**

- 不做"文件管理"这个面向用户的产品功能（上传中心、文件夹、配额）。这是内部治理，不是特性
- 不在本次实现 `oss-architecture.md` §8 的方案 A（scrapper 零凭据、预签名 PUT）。本次做的 file_id 间接层是它的**前置**，做完之后方案 A 的改动面会显著变小
- 不改 `site_display_pref` 与人工偏好相关的任何东西

## 3. 设计决策

每条都附上被否决的替代方案，避免将来重新讨论。

### D1 `user_file` 升格为 `oss_object`，不新增第三张表

**否决：**新建一张统一表，`user_file` 与 `site_asset` 保持原样各记各的。

否决理由：任何表与桶之间都没有事务，账本天然会漂移。再加一本账只会得到**第三份可能失真的记录**，而不是真相。`user_file` 的 `uid` / `origin_name` / `size` / `suffix` 本来就是对象属性，升格是自然演进而非叠加。

### D2 引用方存 `file_id`，不存 object key

引用方共四处：`user_info.avatar_file_id`、`background_image.file_id`、`site_asset`、以及系统默认背景（目前无行）。前两处**已经**是 file_id 了，本次只需把 `site_asset` 拉齐。

价值在于隔离：key 布局将来怎么变（去扩展名、改内容寻址、换前缀），引用方一行不用改，只重写 `oss_object.object_key` 一列。

### D3 `id` 用 UUID，`content_hash` 做 UNIQUE —— **去重来自唯一约束，不来自主键选择**

**否决：**直接用 `content_hash` 做主键。

否决理由：那会当场拆掉 D2 刚建立的隔离层。id 即 hash 意味着引用方与"内容寻址"这个决策强耦合——将来换哈希算法、加盐、改 `sha256:` 前缀格式，全部引用方又得重写一遍，等于在解决完 key 耦合之后原地造出一个 hash 耦合。

而 UNIQUE 索引给的去重能力与主键做 hash **完全等价**：写入路径 `select by content_hash → 命中就复用既有 id`。次要好处：sha256 hex 是 64 字符且完全随机，做主键会让 B-tree 插入局部性变差、每个引用列宽一倍。

### D4 内容属性与引用属性分表

去重意味着"同一份字节只有一行"。两个用户上传同一张图时，这一行的 `uid` 填谁、`origin_name` 填谁的文件名、`create_time` 算谁的？——**都不能填在这张表里**。

所以 `oss_object` 必须是纯粹描述**内容**的表（无属主），所有"每次引用各不相同"的信息下沉到引用方。

这一条推翻了初稿里给 `oss_object` 加 `owner_type` / `owner_id` 的设计——那是"一行一次上传"的假设，与去重不兼容。

> **实施修正：不需要新建 `user_file_ref` 表。**
> 计划原本要造一张引用表承接属主信息。实际检查发现 `user_file.origin_name` **全项目只写不读**，
> 而 `background_image`(uid, file_id, create_time) 与 `user_info.avatar_file_id` 本身就是合格的
> 引用方——各自一行对应一次引用，天然满足本条要求。新建一张只为转存无人读取的字段的表是纯负债。

### D5 引用计数用对账，不用 `ref_count` 列

**否决：**在 `oss_object` 上维护 `ref_count`，删除时递减。

否决理由：引用方散在四张表，计数列在并发下必然对不上，而且每加一个新引用方就多一处漏改。改用 `last_ref_at` + 定期扫描全部引用方表打时间戳，`ListObjects` 结果与账本 diff 出孤儿。

**这条与 D1 是同一件事的两面：缺的不是账本，是对账动作；账本只是这个动作的落脚点。只建表不做对账，等于把问题换个地方放。**

### D6 内容寻址必须与 scrapper 同批上线；**截图不参与内容寻址**

scrapper 现在 `asset_key = SHA256(源URL)`，且 PUT 无条件覆盖。若账本以 content hash 去重而 key 仍是 URL 寻址，会出现**两行不同记录指向同一个 key、其中旧的那一行所描述的字节已被覆盖**——账本在说谎，比重复存储严重得多。

改动本身很小：`pipeline.rs` 在上传前就算好了 `sha256:{hex}`，`asset_key` 改用它即可。但两边必须同批发布。

**截图例外。** URL 寻址有一个没人提但关键的性质：**它自我覆盖，存储量有上界**——同一页面重抓一百次，截图只占一份。改成内容寻址后截图每次都是新对象，无上界增长，而截图的去重收益本来就是零。所以截图保留 URL 寻址，`oss_object` 加一列标明寻址方式，两种形态同表共存。

> **实施修正：用户上传也不参与内容寻址，保持随机 UUID key（`RANDOM`）。**
> 两个理由。其一是合规：P0 刚补上"注销必须删掉用户文件"这条规矩，而去重会让它失效——
> 用户 A 注销时若用户 B 恰好传过同一张图，对象必须保留，"删除我的数据"就删不干净了。
> 其二是风险不对称：用户上传是**真数据**，删错无法再生；站点资产是**可再生**的，重抓一次就回来。
> 为几张壁纸的去重收益去动用户数据的删除语义不划算。
>
> 这条修正连带作废了计划里「P5 前必须把 `updateAvatar` / `deleteUserImage` 改成引用检查」那一项——
> 随机 key 天然一对一，无条件删除始终是对的。

### D7 key 去掉扩展名，mime 存表

同一份字节，A 站声明 `image/png`、B 站声明 `application/octet-stream`，`ext_from_content_type` 会给出两个不同 key → 一个 hash 对两个 key → UNIQUE 冲突。

key 用 `<prefix>/<hash>`（无后缀），mime 存表，签 GET 时用 OSS 的 `response-content-type` 参数还原。`oss-architecture.md` §8 描述方案 A 时已预见到这点（"无扩展名 UUID key 并依赖 site_asset.mime 还原类型"）。

## 4. 目标数据模型

```sql
-- 内容表：一份字节一行，无属主
CREATE TABLE oss_object (
  id            varchar(40)  PRIMARY KEY,           -- UUID，见 D3
  object_key    varchar(512) NOT NULL,              -- 完整 key，不含域名
  content_hash  varchar(80),                        -- sha256:<hex>；存量回填前为空
  addressing    varchar(16)  NOT NULL,              -- CONTENT / SOURCE_URL / RANDOM / LEGACY，见 D6
  source        varchar(32)  NOT NULL,              -- USER_UPLOAD / SCRAPPER / SYSTEM
  size          bigint,
  mime          varchar(128),
  width         int,
  height        int,
  is_vector     boolean      NOT NULL DEFAULT false,
  environment   varchar(16)  NOT NULL,
  created_at    timestamp    NOT NULL DEFAULT now(),
  last_seen_at  timestamp,                          -- 对账：桶里确认还在
  last_ref_at   timestamp,                          -- 对账：还有人引用
  state         varchar(16)  NOT NULL DEFAULT 'ACTIVE'  -- ACTIVE / ORPHAN / DELETED
);

CREATE UNIQUE INDEX idx_oss_object_key ON oss_object(object_key);
-- 注意这一条**现在不能是 UNIQUE**：当前 key 是 sha256(源URL)，"同一张图挂在多个 URL 下"正是
-- 常态（也正是要做去重的原因），此刻加唯一约束会把大量合法数据挡在门外。只有 P5 把 key 改成
-- 内容寻址、"同 hash ⇒ 同 key"成立之后才收紧。partial：存量行 content_hash 为空，不占索引。
CREATE INDEX idx_oss_object_hash ON oss_object(content_hash) WHERE content_hash IS NOT NULL;
CREATE INDEX idx_oss_object_state ON oss_object(state, last_ref_at);

```

引用方共四处，全部存 `oss_object.id`：

| 引用方 | 列 | 说明 |
|---|---|---|
| `user_info` | `avatar_file_id` | 头像 |
| `background_image` | `file_id` | 用户自传背景图（`uid` / `create_time` 都在这张表上，属主信息不必上移） |
| `site_asset` | `file_id`（新增 `varchar(40)`，**可空**，见 §6 约束 B） | 抓取资产 |
| 配置 `default-background-image` | — | 系统默认背景，**库里没有任何行**，按约定拼 key |

最后一行是对账任务最容易漏的一处：漏了它，系统默认背景会被判成孤儿，开启回收后即被删除，而且没有任何用户数据能把它找回来。

## 5. 实施阶段

每阶段独立可发布、独立可回滚。

| 阶段 | 内容 | 完成判据 | 线上风险 |
|---|---|---|---|
| **P0** ✅ | 修复注销不删文件（§1.1 b） | 注销后 `user_file` 行与 OSS 对象均消失 | 低，与重构解耦 |
| **P1** ✅ | 建表 + **旁路账本**：所有写入路径同步写 `oss_object`，读路径一行不改 | 新上传/新抓取都能在 `oss_object` 查到 | **零**，读路径未变 |
| **P2** ✅ | 回填存量 + 对账任务（**只报不删**） | 后台能看到孤儿清单；抽样核对准确 | 零，不执行删除 |
| **P3** ✅ | 读路径切 `file_id`：先 avatar / background，再 `site_asset` | 前端渲染无变化 | 中，风险集中在 `site_asset` |
| **P4** ✅ | `user_file` 表退场 | 无代码引用 | 低 |
| **P5** ⚠️ 代码已发布、**数据未迁完** | 内容寻址（scrapper `asset_key` + 去扩展名 + 长 TTL 签名）+ GC 转正 | 去重生效；孤儿被真实回收 | 中高，需两服务同批发布 |

> **P5 的状态不要按 ✅ 读。** 代码两侧都上线了，但**全量重抓没有完成** —— 库里仍有升级前
> 写入的 `sha256(源URL).<ext>` 形态的 key 与升级后的内容寻址 key 并存，同一份字节两个 key、
> 一个 hash。直接后果是 `2026-08-03_oss_object_hash_unique.sql` 的前置检查会 `RAISE EXCEPTION`
> 中止（这是脚本设计对了，显式失败好过留下半成品索引）。
>
> 排查与修复见 `deploy/migrations/2026-08-04_addressing_and_schema_cleanup.sql` 的注释。
> **不要靠"标 DELETED"绕过** —— 那些旧 key 目前都还有 `site_asset` 指着，标掉即是悬空引用。
> 唯一正确的路径是重抓持有旧 key 的站点，让引用自然转移到新 key 上。

**迁移执行顺序**（手工，无 Flyway）：

| # | 文件 | 时机 |
|---|---|---|
| 1 | `2026-08-01_oss_object.sql` | 随时，先于 P1 版本 API |
| 2 | `2026-08-02_file_id_indirection.sql` | **必须先于 P3/P4 版本 API** —— 新代码读 `oss_object` 而不再读 `user_file`，先发代码会让所有头像和背景图变成空白 |
| 3 | `2026-08-03_oss_object_hash_unique.sql` | **全量重抓完成之后**才能执行；它自带前置检查，条件不满足会直接报错退出而不是留下半成品索引 |
| 4 | `DROP TABLE user_file`（写在 #2 的注释里） | #2 的三条验证查询全为 0、新版跑稳若干天之后 |

### P0 明细
- `UserServiceImpl.del()` 补上：查 `avatar_file_id` 与该用户全部 `background_image` → 删库行 → 删 OSS 对象
- `user_file.deleted` 是死列（全项目无人读，恒为 false）。**不单独发一次 DDL 去删它**——P4 整张表都要退场，为一个惰性列做一次不可逆的 `DROP COLUMN` 是净亏。随表一起消失即可，`OssObjectEntity` 不再复制这一列

### P1 明细（已实现）
- 迁移：`deploy/migrations/2026-08-01_oss_object.sql`
- `OssObjectEntity` / `OssObjectMapper` / `OssObjectServiceImpl`
- 幂等靠 `INSERT ... ON CONFLICT (object_key) DO NOTHING`，**不是"先查再插"**：抓取跑在线程池上，同一 key 的并发写入撞上唯一索引会让 PostgreSQL 整个事务进入 aborted 状态，后续语句再怎么 `runCatching` 也全数失败（`SiteAssetWriter.fillMissingAssets` 的注释详细记过这个坑）
- 记账用 `REQUIRES_NEW` 独立事务。抓取事务回滚**撤销不了** scrapper 在事务之外完成的 PUT，那笔账也就不该跟着回滚——否则正好漏掉"落库失败留下的孤儿"，而那恰恰是账本最该抓住的
- 接入点：`FileServiceImpl` 两个上传方法；`SiteAssetWriter.persist` 在投影之后、任何落库分支之前（对象已经 PUT 进桶，走替换/补齐/整批跳过都要记）
- **双写失败不得影响主流程**，参照 `ai_call_log` 的 `runCatching` 包裹策略
- 只收**裸 key**，完整 URL 一律拒收：`storage_url` 里的存量 URL 有一部分指向外站，记成"我方对象"会让 P2 的对账把它当孤儿清理
- 遗留：用户上传未记 width/height（要多解码一次图片，放到 P2 对账时补）

### P2 明细（已实现）
- `OssUtils.listAllObjects(prefix)` 自动翻页遍历全桶，带 `maxKeys` 安全阀
- `OssReconcileServiceImpl` 四步：补记缺行 → 刷 `last_seen_at` → 桶里没了的标 `DELETED` → 有引用的标 `ACTIVE`、无引用的标 `ORPHAN`
- 全程内存集合运算，写回按 500 一片批量更新。几万个对象逐条 update 会把一轮对账拖成数据库风暴
- **只扫已知前缀**（`FileType.folder` + `scrapper.key-prefix`）。这是安全边界不是优化：桶里别的东西不在名单里就永远不会被判成孤儿
- 入口：`POST /admin/oss-object/reconcile`（手动）+ 每天 04:00 定时。是否回收由配置决定，与谁触发无关
- 回收默认关闭，且有 30 天观察期兜底 —— 那个宽限期挡的是"scrapper 已 PUT、API 还没落行"的时序窗口，没有它，一次撞上对账的正常抓取会被当场清掉刚传上去的图

### P3 明细：`site_asset` 是风险集中点
- `isIdenticalToExisting` **保持不变，不加 `fileId`**。入账按 key 幂等 ⇒ 同 key 必得同 id ⇒ `fileId` 是 `storageUrl` 的函数，比了提供不了新信息；反而在账本暂时不可用时（id 空而 key 有值）会让每次重抓都误判成"变了"
- `SiteAssetResolver` 新增 `objectsOf()` 批量入口，`resolveBatch` 在批量层一次取完账本行。首页一屏几十个图标，在 `presentUrl` 里逐张查库就是教科书式 N+1
- 读取一律 `file_id` 优先、`storage_url` 兜底（覆盖未回填行与存量完整 URL）
- 顺带修掉 `currentBacImgUrl` 返回 `fileId` 字符串却被当 URL 下发的存量 bug

### P5 明细（已实现）
- scrapper `asset_key(content_hash, folder)` → `<prefix>/<folder>/<sha256-of-bytes>`，**无扩展名**
- `screenshot_key` **保持 URL 寻址**，Rust 侧加了一条断言当护栏
- `signAsset(ref, size, immutable)`：内容寻址对象签 24h（`IMMUTABLE_TTL_MILLIS`），其余仍 1h。对象不可变时短有效期换不来正确性，只是削掉缓存命中率，而每次回源都要付一次按次计费的 OSS 图片处理
- `deleteUnreferenced` 的跨书签引用计数**比以前更吃紧**：以前只有同源 URL 才撞 key，现在只要字节相同就撞，跨站共用概率大幅上升。那段代码原样正确，注释已更新说明前提变了
- `content_hash` 收紧成 UNIQUE 单独成一个迁移，因为它依赖一个 SQL 表达不了的前置条件（全量重抓完成）

## 6. 必须守住的三条约束

**A. 唯一键是 `object_key`，写入必须 upsert-by-key。** 理由见 P3 明细第一条。这条不成立，整个重构是负分。

**B. `site_asset.file_id` 必须可空，`OssUtils.signAsset` 的多形态分流不会消失。**

```kotlin
fun renderable(): Boolean = errorMsg == null && (storageUrl != null || resolvedUrl.isNotBlank())
```

大量资产**根本没落对象存储**：PROBE-only 的、指向外站直连的。加上存量完整 URL 行，最终是三态：`file_id` / 外链 `resolvedUrl` / 存量 URL。**文件表不能成为唯一图片来源**，不要指望这次能把 `signAsset` 简化掉。

**C. `FileType` 拆成两半。** 它现在同时管大小上限、允许 MIME、OSS 目录。

实施时这一条收窄了：`folder` **留下**——用户上传的目录确实由 API 决定，它是上传时的输入，是合法职责；scrapper 的前缀才轮不到 API 的枚举来管。真正要消灭的是"**读**的时候现推路径"，即 `UserFile.fullPath` 那个 getter：key 一旦只能靠枚举现拼，改一次目录布局全部存量行的路径就断了。现在 key 落在 `oss_object.object_key` 里，读路径不再推导任何东西。

## 7. 热路径注意

`UserFile.vo()` 在实体方法里直接调 OSS 签名，`BackgroundImageServiceImpl.userImageBackgrounds` 是 `list().mapNotNull { fileMapper.selectById(it.fileId) }`——**已经是 N+1**。

这个模式扩到全系统会很难看：`SiteAssetResolver` 注释里专门写了 "batch, avoids N+1"，首页几十个图标全走文件表，P3 必须一次 `in` 批量取，不能沿用实体内查库的写法。

## 8. 上线后仍需人工完成的事

代码改不动的部分，按顺序：

1. **观察若干轮对账**。`POST /admin/oss-object/reconcile` 或等每天 04:00 的定时任务，在后台核对孤儿清单确实都是垃圾。**这一步不能跳过**——回收是不可逆的，而它的正确性完全取决于引用方收集是否完整
2. 确认无误后打开 `BOOKMARKIFY_OSS_RECLAIM_ORPHANS=true`
3. **全量重抓**，让存量资产从 URL 寻址迁到内容寻址
4. 重抓完成后执行 `2026-08-03_oss_object_hash_unique.sql`
5. 三条验证查询全为 0、跑稳若干天后，`DROP TABLE user_file`
6. `docs/oss-architecture.md` §6.2 那条按 `scrapper/` 前缀的 lifecycle 规则**可以撤掉了**——它当初是"没有 GC 就只能全量过期"的妥协；现在有了按引用判定的真实回收，留着它反而会把仍在使用的资产按 180 天无差别删掉

## 9. 顺带发现（不在本次范围）

- `deploy/migrations/2026-07-31_ai_call_log.sql` 是**空文件**（0 字节，创建于 `f4f31bd9`）。`CLAUDE.md` 要求它在部署 API 前应用，但里面没有任何 DDL，`ai_call_log` 的建表语句实际不存在于仓库任何位置
- `bookmarkify-api/CLAUDE.md` 写着"`LoggingExtensions.kt` provides `logger()` delegate"，但该文件里只有 `val <T> T.log`，没有 `logger()`。而且在 `ServiceImpl` 子类里 `log` 会被 MyBatis-Plus 自带的 `org.apache.ibatis.logging.Log` 成员遮蔽——那个接口没有 `info()` 也没有占位符重载。本次在 `UserServiceImpl` 沿用了 `SiteServiceImpl` / `BookmarkCategoryServiceImpl` 已有的规避方式（显式声明 `private val logger`）
