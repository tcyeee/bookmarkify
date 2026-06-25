# 设计：添加/导入后自动联想并收录相似站点

- 日期：2026-06-25
- 服务：`bookmarkify-api`
- 状态：已批准，待实现

## 1. 背景与目标

用户每次**添加书签**（`addOne`）或**导入书签**（`importBookmarkFile`）时，系统自动对每个书签做「联想」：调 DeepSeek 找出 ≤10 个功能/定位相似的站点，并把它们**收录进系统**——即建立 canonical `bookmark` 记录、抓取元信息、做分类。

收录的相似站点**不关联到任何用户**（不进 `bookmark_user_link`、不进用户布局），只是充实系统的全站书签目录，供后续搜索/发现/分类使用。

### 既有基础（大部分能力已存在，本功能以复用为主）

- `ApiServiceImpl.inferSimilarSites(title, description, host)`：已用 DeepSeek 推荐 8~10 个相似站点，返回 `List<SimilarSite{name, domain, reason}>`，内部 `take(10)` 封顶。
- `BookmarkServiceImpl.ingestOneSimilar(domain)`（private）：**完整的单站收录逻辑**——归一化 host → 本地已存在则 `EXISTS` → `getOrCreateByHost` 建 canonical → `parseBookmark` 抓取 → 抓到正文（`SUCCESS`/`BLOCKED`）才保留，否则 `removeById` 删除（杜绝幻觉/失效域名留库）。
- 异步事件管线：`addOne` → `BookmarkParseAndNoticeEvent`；`importBookmarkFile` → `BookmarkParseAndResetUserItemEvent`；由 `BookmarkParseEventListener`（`@Async(bookmarkParseExecutor)`）消费，落到 `parseAndNotice` / `parseAndResetUserItem`，二者完成抓取后调用 `parseBookmark`（末尾 `bookmarkCategoryService.categorize`）。
- 现有 admin 手动「一键收录」（`adminSimilarSites` + `adminIngestSimilar`，带 WebSocket 进度）保持不动。

本功能本质：把上述「联想 + 收录」从 admin 手动触发，自动化接到用户的添加/导入流程上，并加好「去重 / 隔离 / 限流 / 防递归」护栏。

## 2. 范围决策（已与用户确认）

- **扇出策略：全部联想，靠去重收敛。** 添加和导入的每个「去重后的新站点」都联想 + 收录。不设单次导入上限，靠下列护栏控制成本：每个 canonical 书签全局只联想一次、后台低优先级异步、限流兜底。
- **开关默认开启**（`true`）。提供配置总闸，可随时关闭。
- **一次性语义**：联想标志位在干活前用 CAS 置位；某书签联想过即不再重试（即使该次 DeepSeek 返回空）。不做返回空时的重试。
- **存量书签不回填**（标志位默认 `false`）：仅当存量书签被真实再次添加/导入并触发解析时才联想，渐进铺开，不会一上线就扫全库。
- **不向用户/前端推送任何提示**（纯系统级收录）。

## 3. 架构与数据流

```
addOne / importBookmarkFile
        │ (现有异步事件)
        ▼
parseAndNotice / parseAndResetUserItem      ← 用户入口，抓取+分类完成、已 WS 推送
        │ 末尾 publish BookmarkAssociateEvent(bookmarkId)
        ▼  (新增独立线程池 similarIngestExecutor，线程名前缀 bm-assoc-)
associateAndIngest(bookmarkId)
        ├─ 开关关 / 书签不存在 / parseStatus ∉ {SUCCESS, BLOCKED} → 返回
        ├─ CAS：similar_explored false→true（影响 0 行 → 已联想或并发竞争失败 → 返回）
        ├─ apiService.inferSimilarSites(title, desc, host) → ≤10 域名（空 → 返回）
        └─ domains.distinct().forEach { ingestOneSimilar(domain) }   ← 复用现有
                · 建 canonical + 抓取 + 分类，不可达则删除
                · 内部走 parseBookmark，不发 BookmarkAssociateEvent → 结构上不递归
```

**只在 `parseAndNotice` 和 `parseAndResetUserItem` 两个用户入口发联想事件。** 收录过程内部的 `parseBookmark` 不发事件，因此联想深度恒为 1 层，绝不递归扩散。`parseAndSave`（cron `checkAll` / 启动初始化用）**不发**事件——只有用户主动添加/导入才联想。

## 4. 详细改动清单

### 4.1 数据库迁移

新文件 `sql/2026-06-25-bookmark-similar-explored.sql`（可重复执行）：

```sql
-- bookmark 表新增 similar_explored：该 canonical 书签是否已做过「联想并收录相似站点」
-- 日期: 2026-06-25
-- schema: bookmarkify
-- 说明: 全局每个 canonical 书签仅联想一次的去重标志位。存量不回填(保持 false)，
--      仅当存量书签被真实再次添加/导入触发解析时才联想，渐进铺开。可重复执行。
ALTER TABLE bookmark
    ADD COLUMN IF NOT EXISTS similar_explored boolean NOT NULL DEFAULT false;
```

> 备注：新增列继承 `bookmark` 表已有的 owner/grant，无需为 `bookmarkify_developer` 单独授权（仅新建表才需要）。

### 4.2 实体

`BookmarkEntity.kt` 增加字段（MyBatis-Plus 全局 map-underscore-to-camel 已开启，`similarExplored` ↔ `similar_explored`）：

```kotlin
@JsonIgnore @field:Schema(description = "是否已联想并收录相似站点(全局一次)")
var similarExplored: Boolean = false,
```

### 4.3 配置开关

`ProjectConfig.kt` 增加：

```kotlin
var autoAssociateSimilar: Boolean = true,  // 添加/导入后自动联想并收录相似站点(系统级,不关联用户)
```

`application.yml` 的 `bookmarkify.config` 块增加：

```yaml
auto-associate-similar: true  # 添加/导入后自动联想并收录相似站点(系统级,不关联用户)；置 false 关闭
```

### 4.4 独立线程池

`AsyncConfig.kt` 新增 bean，与 `bookmarkParseExecutor` 隔离，低优先级、超载即丢弃（best-effort，绝不抢占用户抓取线程）。`AsyncConfig` 当前没有类级 logger，需补一个 `private val log = LoggerFactory.getLogger(javaClass)` 供拒绝策略使用：

```kotlin
@Bean(SIMILAR_INGEST_EXECUTOR)
fun similarIngestExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
    corePoolSize = 1
    maxPoolSize = 2
    queueCapacity = 2000
    setThreadNamePrefix("bm-assoc-")
    // 联想是 best-effort 的目录催熟，超载时丢弃任务并记日志，
    // 绝不回退到调用线程执行(那会阻塞 bm-parse 用户抓取线程)。
    setRejectedExecutionHandler { _, _ -> log.warn("[similarIngest] 线程池超载，丢弃一个联想任务") }
    setWaitForTasksToCompleteOnShutdown(false)
    initialize()
}

companion object {
    const val BOOKMARK_PARSE_EXECUTOR = "bookmarkParseExecutor"
    const val SIMILAR_INGEST_EXECUTOR = "similarIngestExecutor"
}
```

> 关键：拒绝策略是「记日志后静默丢弃」，而非 `CallerRunsPolicy`。事件由运行在 `bm-parse-` 线程上的 `parseAndNotice` 发布，若用 CallerRuns 会让用户抓取线程去跑联想，违背隔离目标。丢弃任务只是该站点本轮不联想（best-effort，可接受）。

### 4.5 事件 + 监听

`BookmarkParseEvents.kt` 新增：

```kotlin
/** 用户添加/导入的书签抓取成功后，触发「联想并收录相似站点」。仅由用户入口发布，收录过程本身不发，保证深度 1。 */
data class BookmarkAssociateEvent(val bookmarkId: String)
```

`BookmarkParseEventListener.kt` 新增监听（与现有三个同风格，异常只记日志不上抛）：

```kotlin
@Async(AsyncConfig.SIMILAR_INGEST_EXECUTOR)
@EventListener
fun onAssociate(event: BookmarkAssociateEvent) = runCatching {
    bookmarkService.associateAndIngest(event.bookmarkId)
}.onFailure { log.error("[Async] BookmarkAssociateEvent 处理失败: bookmarkId={}", event.bookmarkId, it) }.let { }
```

### 4.6 服务层

`IBookmarkService` 新增方法：

```kotlin
/** 对指定 canonical 书签联想并收录 ≤10 个相似站点(系统级,不关联用户)。全局每书签仅一次。 */
fun associateAndIngest(bookmarkId: String)
```

`BookmarkServiceImpl` 实现（`ingestOneSimilar` / `inferSimilarSites` 直接复用，零改动）：

```kotlin
override fun associateAndIngest(bookmarkId: String) {
    if (!projectConfig.autoAssociateSimilar) return
    val bookmark = baseMapper.selectById(bookmarkId) ?: return
    // 仅对真实抓到正文的站点联想
    if (bookmark.parseStatus != ParseStatusEnum.SUCCESS && bookmark.parseStatus != ParseStatusEnum.BLOCKED) return
    // CAS 抢占：false→true，影响 0 行说明已联想过或并发竞争失败，直接返回(全局仅一次)
    val won = ktUpdate()
        .eq(BookmarkEntity::id, bookmarkId)
        .eq(BookmarkEntity::similarExplored, false)
        .set(BookmarkEntity::similarExplored, true)
        .update()
    if (!won) return
    val sites = apiService.inferSimilarSites(bookmark.title, bookmark.description, bookmark.urlHost)
    if (sites.isEmpty()) {
        log.debug("[associateAndIngest] DeepSeek 未返回相似站点: bookmarkId=$bookmarkId")
        return
    }
    var ingested = 0
    sites.map { it.domain }.filter { it.isNotBlank() }.distinct().forEach { domain ->
        val status = runCatching { ingestOneSimilar(domain) }
            .getOrElse { log.warn("[associateAndIngest] 收录异常 domain=$domain: ${it.message}"); "SKIPPED" }
        if (status == "INGESTED") ingested++
    }
    log.debug("[associateAndIngest] 完成: bookmarkId=$bookmarkId, 候选=${sites.size}, 新收录=$ingested")
}
```

在两个用户入口末尾发布事件（在 WS 推送之后，作为最后一步）：

- `parseAndNotice(...)` 末尾：`eventPublisher.publishEvent(BookmarkAssociateEvent(bookmarkId))`
- `parseAndResetUserItem(...)` 末尾：`eventPublisher.publishEvent(BookmarkAssociateEvent(entity.id))`

> `ktUpdate()` 的 CAS 更新是单条 `UPDATE ... WHERE id=? AND similar_explored=false`，由 PostgreSQL 行锁保证多用户并发添加同站时只有一个 worker 抢到、其余返回 0 行，避免重复联想/重复打 DeepSeek。
>
> 依赖 MyBatis-Plus 语义：链式 `update()` 经 `SqlHelper.retBool` 返回 `true` 当且仅当**影响行数 ≥ 1**。因此 `won == true` 精确等价于「本 worker 把 `similar_explored` 从 false 翻成了 true」，CAS 去重成立。

## 5. 防递归与去重（正确性核心）

1. **结构防递归**：联想事件只在 `parseAndNotice` / `parseAndResetUserItem` 两个用户入口发布；`ingestOneSimilar` → `parseBookmark` 不发事件 → 收录出来的站点不会再触发联想 → 深度恒为 1。
2. **全局去重**：`similar_explored` 列 + CAS 原子置位 → 每个 canonical 书签最多联想一次；多用户重复添加同站、或同站后续被再次解析，都不会重复联想。
3. **渐进深度扩展（期望行为，非缺陷）**：被收录的相似站点 `similar_explored=false`。若某天有真实用户**主动添加/导入**了它，它才会以「用户入口」身份触发自己的联想——由真实用户兴趣驱动的、每书签一次的渐进扩展。

## 6. 错误处理与背压

全链路 best-effort，任何失败都不影响用户的添加/导入主流程：

- **DeepSeek 失败/超时** → `inferSimilarSites` 返回空 → 本次不收录，记 debug 日志。
- **单站抓取失败/不可达** → `ingestOneSimilar` 返回 `SKIPPED` 并 `removeById` 删除幻觉记录（现有行为）。
- **线程池超载** → 拒绝策略记日志后丢弃该联想任务（该站点本轮不联想）。
- **监听器异常** → `runCatching` 只记 error 日志、不上抛（与现有三个监听一致）。
- **用户响应零影响**：事件发布是廉价的内存操作，真正的联想/抓取全在 `bm-assoc-` 池异步执行。

**限流来自池规格本身**：`maxPoolSize=2` → 跨书签最多 2 个联想并行；单次联想内部 `forEach` 顺序抓 ≤10 站。叠加用户抓取池（`bm-parse-` 4~8），对 scrapper 的总并发可控。大批量导入会在后台**缓慢消化**——符合「催熟系统目录、不赶时间」的定位。

## 7. 不做（YAGNI）

- 不向用户/前端推送 WebSocket（纯系统级收录）。
- 不做按 domain 的失败重试 / 对账 cron（best-effort；如需可后续加）。
- 不做多跳 / 递归联想（深度恒为 1）。
- 不改动 admin 手动「一键收录」链路。
- 不回填存量书签的 `similar_explored`。

## 8. 验收标准

- 单条添加一个**新（或已过期 >1 天）**书签：抓取完成后，后台在 `bm-assoc-` 线程内调 DeepSeek，得到 ≤10 个相似站点，其中可达的成为新的 canonical `bookmark` 行（已抓取、已分类），**均未进入该用户的 `bookmark_user_link` / 布局**；源书签 `similar_explored=true`。
- 导入 N 个书签：每个「去重后新解析」的源书签都触发同样流程，在后台低优先级池中逐步消化；每个 canonical 书签全局只联想一次。
- 收录出来的相似站点**不会**再触发各自的联想（无递归）。
- `bookmarkify.config.auto-associate-similar=false` 时，整个联想/收录链路关闭。
- 用户添加/导入接口的响应时延与改造前一致（完全异步）。
- 多用户并发添加同一新站点时，DeepSeek 联想只发生一次（CAS 去重生效）。
