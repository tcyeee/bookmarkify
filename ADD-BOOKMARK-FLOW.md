# 添加书签的完整流程

> 起草日期：2026-08-03 · 范围：`bookmarkify-web` → `bookmarkify-api` → `bookmarkify-scrapper` 三个子系统的端到端链路
> 前置阅读：根 [`CLAUDE.md`](CLAUDE.md) 的「Site Assets & the Scrapper Contract」（抓取结果如何变成图标）

这条链路的形状由一个约束决定：**抓一个网页要几秒到几十秒，而用户按下回车后必须立刻看到东西。**
所以它被切成「同步段」与「异步段」，中间用一个 `BOOKMARK_LOADING` 占位节点缝合，靠 WebSocket 送回结果。
本文按时间顺序走一遍，并在 §7 给出每一处失败的兜底路径——那是这套设计里最容易被改坏的部分。

---

## 0. 全景

```
用户输入网址
   │
   │ ① POST /bookmark/addOne?url=…                （同步，几十毫秒）
   ▼
BookmarkServiceImpl.addOne
   ├─ 规范化 URL → canonical 四元组
   ├─ getOrCreateByUrl  ── 全站共享的 bookmark 记录
   ├─ 判重（已收藏 / 已在导入队列 → E126）
   ├─ 事务写入 user_layout_node + bookmark
   └─ needParse ? 返回 LOADING 占位 : 返回完整数据
   │
   │ ② 事务提交后发布 BookmarkParseAndNoticeEvent
   ▼
bookmarkParseExecutor（8~32 线程，队列 500）
   └─ parseAndNotice → parseBookmark（ParseLock 互斥）→ parseByApi
        │
        │ ③ POST /scrape                          （读超时 60s）
        ▼
   bookmarkify-scrapper
        阶段1 取回 HTML（Layer1 裸抓 → 反爬救援阶梯 → Layer2 无头）
        阶段2 纯提取（HTML → 元数据 + 图片"声明"）
        阶段3 网络富化（manifest 回填 + 图片下载/上传 OSS）
        │
        │ ④ ScrapeResponse（含 storageKey，不含 URL、不含 role）
        ▼
   落库：bookmark 主表 + scrape_snapshot + site_page_meta + site_asset
        │
        │ ⑤ 节点翻成 BOOKMARK，SocketUtils.homeItemUpdate
        ▼
   WebSocket HOME_ITEM_UPDATE ──► 前端 replaceContent()，占位磁贴就地变成书签
```

离开主链路的两条支线（§6）：解析成功后另发 `BookmarkEnrichEvent`（分类 + NSFW）与 `BookmarkScreenshotEvent`（封面截图），都不阻塞用户。

---

## 1. 前端：从输入框到占位磁贴

### 1.1 入口

`components/launchpad/AddOneDialog.vue` 是唯一的单条添加入口，由 `sysStore.addBookmarkDialogVisible` 打开，触发点有四处：
`AddBookmarkFab.vue`、`CommandPalette.vue`、`SettingsModal.vue`、`pages/index.vue`。

对话框同时承载两个动作：

| 动作 | 接口 | 语义 |
|---|---|---|
| 添加 | `bookmarksAddOne(url)` → `POST /bookmark/addOne` | 按网址新建/复用 canonical 记录 |
| 关联 | `bookmarksLinkOne(id)` → `POST /bookmark/linkOne` | 直接挂一条别人已收录的 canonical 记录 |

两者都是 `POST` 而非 `GET`——它们会写三张表，`GET` 有被浏览器预取 / 代理缓存 / 爬虫重放的风险。

### 1.2 提交前的两道本地检查

1. `isBookmarkableUrl()`（`server/utils/index.ts`）：只收 http(s)，拒绝含空白的输入，未显式写协议时要求主机名"像个域名"，并挡掉 `https://a..b` 这类 `new URL` 会放行的畸形主机名。
2. `canonicalUrlKey()` 判重：把网址归一成 `host + path + 排序后的 query` 与本地已有节点比对。规则**刻意弱于**后端（不剥离追踪参数），所以只会漏判、不会误拦——判重的唯一权威始终是后端。

搜索结果列表同样按 `ownedBookmarkIds` 过滤掉已收藏项，避免点下去只能得到一个错误提示。

### 1.3 后端同步返回的两种形状

`handleSuccess(res)` 按 `res.typeApp` 是否为空分流，这是前端判断"这次要不要等 WebSocket"的唯一依据：

| `typeApp` | 含义 | 前端动作 |
|---|---|---|
| 有 | 无需重抓，数据已就绪 | `addNode()` 直接渲染，**本次不会有 WebSocket 推送** |
| 无 | 后端返回了 LOADING 占位 | `addLoading()` + `watchForResolution()`，等推送 |

`typeApp` 存在但 `isActivity === false` 时额外弹一条提示：说明命中了后端「近期已检测无法访问」的 10 分钟跳过窗口，本次根本没发起抓取。不说明的话用户会以为这条书签没走正常解析。

---

## 2. API 同步段：一次 HTTP 请求里做完的事

`BookmarkServiceImpl.addOne(url, uid)`，全程不碰网络，几十毫秒返回。

| 步 | 做什么 | 关键点 |
|---|---|---|
| 1 | `WebsiteParser.urlWrapper(url)` | 补全协议、剥离追踪参数、query 按 key 排序、丢弃页内锚点 |
| 2 | `getOrCreateByUrl(wrapper)` | 按 **(siteId, urlPath, urlQuery, urlFragment)** 四元组取或建 canonical 记录 |
| 3 | `assertNotAlreadyLinked(uid, bookmark)` | 已收藏 → E126；顺带查导入队列（见下） |
| 4 | 判定 `needParse` | `checkFlag() \|\| needRecheckOnAdd()` |
| 5 | 事务写 `user_layout_node` + `bookmark` | 两条必须原子 |
| 6 | 返回 VO，并在事务提交后发事件 | |

**为什么第 2 步刻意留在事务之外**：它靠"捕获唯一键冲突后回查"来收敛并发插入，而 PostgreSQL 里事务内一旦触发约束冲突，整个事务进入 aborted 状态，回查那条 SELECT 也会一并失败。代价是极端情况下多出一条无人引用的 canonical 记录，下次添加同一网址会复用它。

**为什么第 5 步必须是一个事务**：分开写时第二条失败，用户桌面上会留下一个没有任何书签数据的孤儿节点——`layout()` 按 `layoutNodeId` 找不到对应的 `BookmarkShow`，前端只能渲染出一个点不开也删不掉的空格子。

**判重为什么查两次**（`assertNotAlreadyLinked` / `assertNotPendingImport`）：主检查按 canonical `bookmarkId` 比对（同一页面的 `github.com/x`、`https://github.com/x/` 写法各异但记录是同一条）；而批量导入写下的关联行 `page_id` 是字符串常量 `'LOADING'`，永远匹配不上主检查，导入正在跑时手动添加同一网址就会多出一个磁贴。第二道检查先用 host 子串在 SQL 侧收窄，再把回捞的行规范化后比四元组。`linkOne` 走完全相同的两道检查。

### `needParse` 的判定

```
checkFlag()          = updateTime 为空（从没抓过）或距今 ≥ 24h
needRecheckOnAdd()   = 不是 PENDING、没被人工认证、且「不是(SUCCESS 且可用)」
                       ＋ 距上次检查 ≥ 10 分钟（DEAD_RECHECK_COOLDOWN_MINUTES）
```

`needRecheckOnAdd` 的存在是因为：用户主动添加这个网址，本身就是「这站点现在应该是好的」的强信号，不该让他拿到一条早先判失效的数据干等定时任务。10 分钟冷却则是防止一个真的挂了的站点被反复添加时反复抓取。

`needParse` 为假时，节点直接以 `BOOKMARK` 落库并同步返回完整数据——省掉了原先「先插 LOADING 再 update 成 BOOKMARK」那次多余的写。

---

## 3. API 异步段：解析线程池上的一次抓取

`BookmarkParseAndNoticeEvent` → `BookmarkParseEventListener.onParseAndNotice` → `parseAndNotice(uid, bookmarkId, userLinkId, nodeId)`。

跑在 `bookmarkParseExecutor`（core 8 / max 32 / 队列 500）。队列打满后 `CallerRunsPolicy` 会让调用线程同步执行——而调用线程往往就是 Tomcat 请求线程，所以队列容量与 `drainStuckLoading` 的投递余量（`DRAIN_QUEUE_HEADROOM = 50`）是一组必须一起看的参数。

链路：

```
parseAndNotice
 └─ parseBookmark(entity)                    ← ParseLock（Redis SETNX，per bookmarkId）
     └─ parseBookmarkExclusively
         ├─ verifyFlag=true      → 直接返回，跳过抓取
         ├─ 非 DOMAIN 链接        → 标记可用，不抓取（本地/IP 类）
         └─ parseByApi(entity)   → apiService.queryWebsiteInfo → POST /scrape
```

**ParseLock 保证同一 canonical 书签同时只有一次抓取在跑。** 多个用户同时添加同一 URL 时会各发一个事件；没有这把锁，多个 `SiteAssetWriter.persist` 的「先删旧资产再插新资产」事务会交错执行，资产行可能翻倍或整体丢失，OSS 孤儿回收的引用计数还会把对方刚上传的对象当成无人引用删掉。抢不到锁时**直接返回库里当前的记录，不阻塞等待**——调用方照常翻转节点并推送，用户先看到基础信息。

**不做抓取前的 ping。** 两条解析路径本来就会把抓不到的情况自行收口成 `UNREACHABLE`，ping 对结果的正确性没有贡献，只是在每一条书签上白加一个最长 15s 的往返。ping 保留给定时活性巡检。

---

## 4. scrapper：`/scrape` 的三个阶段

请求体由 `ApiServiceImpl.scrapeRequest()` 组装，关键字段：`assets.download = UPLOAD`（图片必须落我方 OSS）、`extract.assets = true`、`screenshot.enabled = false`（主链路不截图）、`cache.mode`。请求体用 `deny_unknown_fields`，拼错字段直接 422。

### 阶段 1：取回 HTML

前置两道缓存：**负缓存**（近期失败过的 URL，60s TTL，直接 502 `RECENTLY_FAILED`）与**结果缓存**（`CacheMode::Bypass` 可绕过，管理后台的"重试"必须用它）。

`RenderMode::Auto` 下的路径与救援阶梯：

```
Layer 1 裸抓（reqwest，10s 超时）
  ├─ 成功且有 <title>            → 用它
  ├─ 成功但没有 <title>          → 页面多半靠 JS 渲染，回退 Layer 2
  └─ 403/406/412（反爬）        → 救援阶梯，按代价从低到高：
        ① siteapi.rs 站点官方 API（几 KB）
           拒的是机房出口 IP 时，这是唯一还走得通的门（B 站即此场景）
        ② 无头浏览器（~400MB）
           拒的是"不像浏览器"时才有用；先查 headless_futile 熔断（按 host，900s）
        ③ 都不行 → 报 Layer 1 的原始错误（现场最完整）
```

Layer 1 的客户端必须同时有 `redirect::Policy::none()`（手动逐跳跟随才能产出 `fetch.redirectChain`）和 `cookie_store(true)`（正因为手动跟随，很多站点在 301 跳发的反爬 cookie 才不会丢）。它还会发一整套浏览器请求头，并在子路径被 403/406/412 挡住时预热一次源站根路径再重试。

**无头导航若同样是非 2xx，必须拒收那份 HTML**——拦截页有标题有正文，收下就会变成一条标题叫"出错啦"的书签，比直接报错糟得多。

### 阶段 2：纯提取

`extract::extract_page(html, final_url, opts)`，离线可测，无网络。产出元数据与图片**声明**——每张图带 `extractor`（它来自哪个标签/字段），**不带 `role`**。站点 API 救援回来的结构化数据摊平成同一个 `Extracted`，让阶段 3 原样复用。

### 阶段 3：网络富化

- 拉 `manifest.json`，回填 `shortName` 与 `MANIFEST_ICON`
- 按 `assets.download` 处理图片：`PROBE` 也会下载正文（算 `contentHash` 和真实像素尺寸必须读字节）只是算完即丢；`UPLOAD` 则写入 OSS 并回报 `storageKey`
- 子资源（图片 / manifest）走 `apply_subresource_headers`，与页面导航的头集不同；`Referer` 必须由**页面** URL 推导

各阶段的局部失败一律降级为 `diagnostics.warnings`，不让增量信息拖垮整次抓取。

---

## 5. 回程：落库 → 推送 → 就地替换

`parseByApi` 拿到 `ScrapeResponse` 后：

1. `vo.applyTo(bookmark)` 写主表；`appName` 优先取 manifest 的 `short_name`，拿不到才退回 DeepSeek 推断（最长 10s，**必须在事务外**）
2. `restoreLockedFields(manual)` 还原被人工锁定的字段（`page.locked_fields`）——抓取路径可以读锁，绝不能写锁
3. **一个短事务**里同时提交主表与 `SiteAssetWriter.persist`（`scrape_snapshot` + `page_meta` + `site_asset`）。分成两个事务时，中间失败会留下 `parse_status=SUCCESS` 却一条资产都没有的书签，而三个对账任务都按 `parse_status` 过滤，没有任何一个会回来补它
4. `siteService.applyCrawledMeta` 回写站点级品牌名（首页可覆盖，深链只在站点侧还没值时回填）

回到 `parseAndNotice`：

```
resolved.parseStatus == PENDING ?   → 直接 return，节点保持 LOADING（见 §7）
布局节点已被用户删掉 ?              → 直接 return
否则 → showForDesktop(userLinkId) 组装 BookmarkShow（含 OssUtils 签名后的图标 URL）
      → 节点 type 翻成 BOOKMARK
      → SocketUtils.homeItemUpdate(uid, vo)
```

`SessionManager.send` 广播给该用户**当前全部**在线连接（键 `realm:uid` → 连接列表）。**推送是即发即忘的：没有离线队列，没有服务端重试**，用户不在线就是丢了。

前端 `websocket.store.ts` 收到 `HOME_ITEM_UPDATE` → `replaceContent()` 就地替换节点并 `clearResolutionWatch()`。替换必须产生**新的对象引用**，直接改嵌套字段不会触发 Vue 重渲染。

---

## 6. 解析之后的两条支线

两者都在 `parse_status == SUCCESS` 时发出，用户不等它们。

| 事件 | 线程池 | 做什么 | 为什么拆出去 |
|---|---|---|---|
| `BookmarkEnrichEvent` | `bookmarkEnrichExecutor`（2~8，队列 10000） | 分类打标 + NSFW 判定（DeepSeek） | 各要一次 10s 往返，而用户根本看不到；留在主链路等于每条书签多占解析线程 20s |
| `BookmarkScreenshotEvent` | `bookmarkScreenshotExecutor`（**单线程**，队列 200） | 页面截图作封面 | 强制走无头浏览器，而对端 Chrome 由一把全局互斥锁串行化；单线程是与对端实际并发度对齐的硬约束 |

截图落库走 `SiteAssetWriter.upsertScreenshot` 而**不是** `persist`——后者会整体替换 PAGE 层资产，把主抓取刚写好的社交图一并删掉。

> ⚠️ 无头截图目前仍只能截出裸 HTML（无 CSS、无图、无 Web 字体），且**没有任何症状**（HTTP 200、字节数正常、`storageKey` 正常）。定位前不要对外宣称截图可用，详见根 `CLAUDE.md`。

---

## 7. 失败与兜底矩阵

这是全文最该记住的一节。**任何一处收口方式改错，症状都是同一个：用户桌面上一个永远转圈的格子。**

| 失败点 | 书签状态 | 节点状态 | 谁来兜底 | 多久 |
|---|---|---|---|---|
| 目标站点抓不到（E304） | `UNREACHABLE`, `isActivity=false` | 照常翻 `BOOKMARK` 并推送 | 灰显磁贴，`retryUnreachableBookmarks` 每小时:30 复查 | 立即可见 |
| **我方抓取服务不可用（E307）** | **刻意保持 `PENDING`** | **刻意保持 `LOADING`** | `drainStuckLoading`（且**清零**该行的重试计数） | ≤30 分钟 |
| 网址指向内网（E308，仅本地解析路径） | `UNREACHABLE` | 照常收口 | 不重试——这是我方的安全决策，不是站点的问题 | 立即 |
| 解析链路抛未预期异常 | 降级为 `UNREACHABLE` | 照常收口 | 同第一行 | 立即 |
| 解析事件丢失（重启 / 池饱和） | `PENDING` | `LOADING` | `checkAll`（每 5 分钟）+ `drainStuckLoading`（每 30 秒） | ≤30 分钟 |
| 补投递 5 次仍不收口 | 不限 | 强制翻 `BOOKMARK` | `terminateExhaustedLoading` 就地终结成无源书签 | ≤约 25 分钟 |
| WebSocket 推送时用户不在线 | `SUCCESS` | 已是 `BOOKMARK` | 前端重连后 `refresh()` 补拉整棵树 | 重连即刻 |
| WebSocket 半开（连接假活） | `SUCCESS` | 已是 `BOOKMARK` | 客户端心跳看门狗（3 个周期无任何帧 → 强制重连） | ≤15 秒 |
| 推送丢了且前端没察觉 | `SUCCESS` | 已是 `BOOKMARK` | `watchForResolution` 递增轮询（30s→5min，8 次，共约 35 分钟） | 30 秒起 |
| 用户在抓取途中删掉了该书签 | 正常写完 | 节点已不存在 | `parseAndNotice` 查不到节点即早退 | — |

**E307 那一行是整张表里最反直觉、也最容易被改错的。** 抓取服务没起 / 配错 / 限流时，这是**我方故障，不是这个网站挂了**，所以 `parseByApi` 刻意不写 `UNREACHABLE`、`parseAndNotice` 刻意不翻转节点。一旦有人"顺手"把节点收口成 `BOOKMARK` 并推送，用户看到的是一个永久定格的空书签：不仅把我方故障误报成网站失联，节点还从 `BOOKMARK_LOADING` 消失，**永远脱离 `drainStuckLoading` 的重投递范围**——之后即使抓取成功，也没有任何机制会把结果回传给这个已经"收口"的节点。

同理，`classifyScrapperError` 的 `else` 分支就是 E307，所以**每新增一个 scrapper 错误码都必须回头看它**：漏掉一个本属于目标站点的码，代价不是"分类不准"，而是"这条书签转圈半小时"。`RECENTLY_FAILED` 就这么漏过一次。

### 补投递的重试预算（`dispatch_attempts`）

`drainStuckLoading` 每次补投递给 `bookmark.dispatch_attempts` 加一，超过 5 次就由 `terminateExhaustedLoading` 就地终结。

**为什么需要上限**：`findStuckLoading` 是 `ORDER BY created_at ASC LIMIT n`，而补投递锁只让在途的那些被跳过、并不改变它们仍然排在最前面这一事实。于是一批「永远收不了口」的记录会稳定占满那 n 个名额，排在后面的行一轮都轮不到——一次导入的后半截可能永远抓不完，而日志里看不出任何异常。

**为什么 E307 必须清零**（`forgiveDispatchAttempt`）：重试上限要防的是「这条记录本身有问题」，而 E307 说明**我方**坏了。不清零的话，一次几十分钟的 scrapper 故障会把积压里每条记录的预算耗光，故障恢复时它们已经被当作"重试到上限"终结成无源书签了——把一次运维故障变成了一批永久降级的数据。

### 转圈中的 SLI

`drainStuckLoading` 每轮输出「此刻有多少条在转圈、最久的转了多久、其中多少是导入积压」（`stuckLoadingStats`）。超过 30 分钟陈旧阈值还在转的会打 `warn`。

这是整条链路唯一真正的成败指标，而在此之前它没有任何一处被观测：`scrapper_call_log` 记的是单次调用、`page_ping_log` 记的是巡检，都回答不了「用户现在还在等的有几条」。上面那张兜底矩阵因此只是"设计上应该成立"，线上无从验证。

### 前端侧兜底的三件事（它们是一组，不是可选加固）

1. **重连补拉**：`onopen` 判断是重连（而非首次连接）就 `bookmarkStore.refresh()`——断线期间推送的东西已经没了
2. **双向心跳 + 看门狗**：服务端回 `pong`，客户端记录 `lastMessageAt`，3 个心跳周期无任何帧就主动 close 触发重连。只发不收的心跳证明不了任何事：半开连接下 `readyState` 仍是 OPEN、`send()` 也不报错
3. **`online` / `visibilitychange` 绕过退避预算**：5 次退避累计约 31s，真断网超过它就再也不会自己回来

`watchForResolution` 的监听由 `setLayout` / `addImportLoadingBatch` / `plugins/auth.ts` **集中挂载**，不是由每个插入占位的调用方各自负责——后者正是 localStorage 恢复出来的 LOADING 节点没人看着的原因。

---

## 8. 批量导入：同一套占位，不同的投递

`components/setting/BookmarkManage.vue` 上传浏览器书签文件：先 `bookmarksUploadPreview()`（服务端按 canonical 四元组标出 `isDuplicate`），用户取消勾选后再 `bookmarksUpload(file, skipUrls)`。

与单条添加的关键差异：

| | 单条添加 | 批量导入 |
|---|---|---|
| 关联行的 `page_id` | 真实 canonical id | 字符串常量 `'LOADING'` |
| 是否发解析事件 | 发 | **完全不发** |
| 消费通道 | 事件 + `drainStuckLoading` 兜底 | 只有 `drainStuckLoading` |
| 陈旧阈值 | 要等 30 分钟才判定"事件丢了" | 无需等待，写下就等着被捞 |

**导入不发事件是刻意的**：几千条逐个投递会把解析池连同队列一起打满，最终回退到调用线程——也就是 HTTP 请求线程——同步跑完整段抓取。`drainStuckLoading` 改为按线程池**当前空闲队列容量**投递，并留出余量给交互式的 addOne。

导入路径的收口走 `parseAndResetUserItem`：抓完后把 `page_id` 从 `'LOADING'` 重绑到真实 canonical id，再翻转节点。网址本身就解析不出来的（`javascript:` 小书签、`about:` 页面，导入时不做过滤）必须就地终结成一条无源书签，否则 `drainStuckLoading` 会无限重投同一条。

---

## 9. 改动前必读的不变量

1. **`BOOKMARK_LOADING` 是用户可见的卡死状态，也是唯一的兜底抓手。** `drainStuckLoading` 按节点类型而非 `parse_status` 选行，正是因为"书签抓取成功、但重绑用户关联或翻转节点失败"时 `parse_status` 是 `SUCCESS`，任何按状态筛选的任务都覆盖不到。
2. **E307（我方故障）与 E304（站点故障）绝不能合并。** 见 §7。
3. **同一 canonical 书签的抓取必须互斥**（`ParseLock`），否则资产行会翻倍或丢失。
4. **`scrapper` 只报事实，`API` 定策略。** `extractor` 由 scrapper 给，`role` / `quality` / 签名 URL / 缩放全在 API 侧（`AssetRolePolicy`、`OssUtils.signAsset`）。新增 `extractor` 取值必须给 `AssetRolePolicy.TABLE` 补一条映射，否则那张图会被静默降级丢掉——`AssetRolePolicyTest.every extractor has an explicit role mapping` 会拦下漏配。
5. **WebSocket 推送不可靠，客户端必须能自愈。** 服务端没有离线队列也不重试；§7 那三件事缺一不可。
6. **新增一种推送就新增一个消息类型**，不要复用 `HOME_ITEM_UPDATE`——三种布局消息的 payload 形状不同，客户端正是靠类型区分的。
7. **判重永远落在 canonical 四元组上**，不是 URL 字符串；前端的本地判重只是省一次往返，规则必须弱于后端。
8. **判重的权威是唯一索引 `uk_bookmark_uid_page`，不是 `assertNotAlreadyLinked`。** 那道检查是 check-then-act，两个并发请求可以同时通过；此前真正挡住重复磁贴的其实是 `addOne` 上那个 1 秒的 `@Throttle`——而限流是 UX 设施，参数会因为"加书签太慢"被调宽，`ThrottleAspect` 在 Redis 故障时更是**明确降级放行**。正确性不能挂在限流器上。新增写 `bookmark` 的入口时，记得走 `insertNodeAndLink`（它把 `DuplicateKeyException` 翻成 E126）。
9. **改 `parse_status` 只能通过 `markParseSucceeded()` / `markParseUnreachable()`。** 那四个字段之间有约束（SUCCESS 必然 `isActivity=true` 且 `parseErrMsg` 为空），而漏掉调度列那一句不会报任何错——那条记录的 `next_check_at` 就停在旧值上，要么被每轮巡检重复选中，要么再也不被选中。这五行曾被逐字复制十遍。
10. **`page_id = 'LOADING'` 表示"等着被绑定"，NULL 表示"确定没有 canonical 记录"。** 无源书签终结时必须把标记清成 NULL（`clearUnboundMarker`），否则 `assertNotPendingImport` 会永远把它当成还在导入队列里，用户之后添加同一个网址会撞上一个假的 E126。
11. **本服务当前只能单实例运行。** `SessionManager` 的会话在进程内存里，`@Scheduled` 没有分布式锁。`SingleInstanceGuard` 会在检测到第二个实例时每分钟打一条 error——它只报警不阻止启动，看到那条日志就是真的出问题了。要横向扩容必须先接入 ShedLock **加上** WebSocket 推送的 Redis pub/sub 扇出，两者缺一不可。
12. **显示偏好是全站级的，用户级差异只能存在于 `bookmark`。** `page` 是全站共享的可变记录：A 用户添加触发的重抓会改变 B 用户桌面上那条书签的标题和图标。`locked_fields` / `verifyFlag` 是**管理员级**的锁，解决不了"两个用户对同一页面有不同期望"。`site_display_pref` 按 `(bookmark, display_mode)` 而非 `(user, bookmark, mode)` 建键是同一个决定的延伸——真要做用户级图标覆盖时，这里需要一次迁移。

---

## 附：涉及的主要文件

```
bookmarkify-web/
├── components/launchpad/AddOneDialog.vue     入口 + 本地校验/判重
├── stores/bookmark.store.ts                  节点树、LOADING 占位、兜底轮询
├── stores/websocket.store.ts                 连接、心跳看门狗、重连补拉
└── plugins/auth.ts                           启动时拉取 + 补挂兜底监听

bookmarkify-api/.../
├── controller/bookmark/BookmarkController.kt        /bookmark/addOne · linkOne
├── server/impl/BookmarkServiceImpl.kt               addOne / parseAndNotice / parseByApi
├── server/impl/ApiServiceImpl.kt                    /scrape 调用 + classifyScrapperError
├── config/event/BookmarkParseEvents.kt              四种事件
├── config/async/AsyncConfig.kt                      四个线程池及其硬约束
├── config/websocket/SessionManager.kt               多连接广播
└── server/asset/SiteAssetWriter.kt                  抓取结果落库

bookmarkify-scrapper/crates/scraper-service/src/
├── main.rs        /scrape 编排（三阶段 + 反爬救援阶梯）
├── scraper.rs     Layer 1 抓取 + SSRF 防护
├── siteapi.rs     站点官方 API 救援
├── headless.rs    Layer 2 无头浏览器
├── extract.rs     纯提取
└── pipeline.rs    manifest + 图片富化
```
