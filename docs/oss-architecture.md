# OSS 架构评审与整改记录

> 评审日期：2026-07-29 · 范围：`bookmarkify-api` 与 `bookmarkify-scrapper` 中所有对象存储相关代码
> 结论：功能可用，但**存储的身份、寻址与凭据职责错误地下沉到了 scrapper**，由此派生出两个 P0 与多个 P1。

## 1. 现状

两个服务各自直连同一个阿里云 OSS 桶，实现方式完全不同：

| | `bookmarkify-scrapper` | `bookmarkify-api` |
|---|---|---|
| 实现 | 手写 HMAC-SHA1 V1 签名 + reqwest PUT（`src/oss.rs`） | 官方 `aliyun-sdk-oss:3.17.4`（`utils/OssUtils.kt`） |
| 环境变量 | `OSS_ACCESS_KEY_ID` 等 5 个 | `BOOKMARKIFY_ALIYUN_OSS_*` 5 个 |
| key 前缀 | `bookmarkify/scrapper/**` | `bookmarkify/{avatar,bac,logo,og}/**` |
| 能力 | 只上传 | 上传 + 签名下发 + 删除 + 图片处理 |
| 缺配置时 | `from_env()` 返回 `None`，静默降级 | 启动失败 |

桶是**私有读**：库里存的地址未签名，直接访问 403，必须经 `OssUtils.signWithResize` 换成限时签名地址。

## 2. 核心判断

> scrapper 该做的只有"把字节 PUT 进对象存储"这一件事。
> 它现在还额外决定了 **key 长什么样、URL 用哪个域名、用哪种签名算法、持有多大范围的凭据** —— 这四件事都属于 API。

被否决的替代方案：让 scrapper 用已有的 `AssetDownload::Inline` 回传 base64、由 API 统一上传。
默认 `max_count = 20`、`max_bytes = 2 MiB`（`contract.rs:226,230`），最坏情况约 `20 × 2MiB × 1.33 ≈ 53 MB`
的 JSON 要进 JVM 堆，作为默认路径不可接受；且字节本就在 scrapper 手里（`PROBE` 模式为了算
`contentHash` 和真实像素尺寸也必须下载完整正文），原路返回纯属浪费。**传输职责留在 scrapper 是对的。**

## 3. 发现清单

编号沿用评审时的顺序；「根因」列标出哪些直接源于上述越界。

| # | 级别 | 问题 | 根因 | 处置 |
|---|---|---|---|---|
| 1 | P0 | 两服务域名配置隐式耦合，不一致时静默降级为 403 | 越界③ | ✅ 已修（B2/B3/B4） |
| 2 | P0 | 对象只增不减，lifecycle 是未兑现的注释 | 部分 | ◐ 用户数据已修；站点资产**刻意**交给 lifecycle，见下 |
| 3 | P1 | ~150 行死代码，且包裹着安全关键逻辑 | — | ✅ 已修（C1） |
| 4 | P1 | scrapper 持有整桶可写的长期凭据 | 越界④ | ⚠️ 需执行 §6 的 RAM 配置 |
| 5 | P1 | 签名 URL 每次都变，打死浏览器/CDN 缓存 | — | ✅ 已修（C2） |
| 6 | P2 | 两套签名实现分叉（手写 V1 / SDK） | 越界④ | 🕒 延后，见 §7 |
| 7 | P2 | 上传重试不区分错误类型 | — | ✅ 已修（C4） |
| 8 | P2 | `OssUtils` 静态可变全局状态，不可测 | — | 🕒 延后，见 §7 |
| 9 | P2 | 未使用的 12h 缓存类型是"已上膛的枪" | — | ✅ 已修（C5） |
| 10 | P2 | key 用源 URL 哈希而非 `contentHash` | — | 🕒 延后，见 §7 |

### 1（P0）域名耦合导致静默 403

- scrapper 返回**拼好的完整 URL**：`oss.rs` `format!("{}/{}", self.base_url, key)`，`base_url` 来自 `OSS_BASE_URL`
- API 靠 **host 字符串匹配**判断"这是不是我的对象"：`OssUtils.ownOssObjectKey()`
- `SiteAssetResolver.presentUrl()` 匹配不上就原样返回 —— 私有读桶上前端直接 403

两个值是**独立环境变量、零校验**，且 scrapper 文档示例（`https://<bucket>.oss-cn-hangzhou.aliyuncs.com`）
与 API 配置注释里的自定义域名恰好不匹配。失败路径 `runCatching{}.getOrNull()` 完全静默，
没有日志也没有告警，表现只是"图不显示"。

**附带发现的存量 bug：** `ScrapeResponseExt.kt` 的 `faviconUrl` / `logoUrl` / `socialUrl` /
`screenshotUrl` 返回的是**未签名**的 `storageUrl`，直接进入 `BookmarkLivenessVO` /
`WebsiteLivenessCheckVO` 返给浏览器与 admin（`BookmarkServiceImpl.kt:494`、
`AdminWebsiteController.kt:64`）。这条路径上私有读桶必然 403，与本次重构无关，独立存在。

### 2（P0）对象只增不减

全项目只有一处删除（`UserServiceImpl.kt` 换头像清旧图）。删书签只 `removeById`；
删背景图只删 DB 行；站点换 logo URL 后旧对象因 key = `SHA256(源URL)` 而永久孤立；
scrapper 侧根本没有 delete 能力。`oss.rs` 顶部注释把"以后可以加一条 lifecycle 规则"
写成了设计依据，但 `deploy/` 下并无该配置。除成本单调增长外，用户删图/删号后
对象仍在桶中且 key 可推导，构成合规风险。

**两类对象要分开处置，本次刻意只修了一类：**

- **用户上传的文件**（头像、背景图）是真数据，删了就没了，必须随 DB 行同步删除。
  已补上背景图的删除路径（头像原本就有）。
- **站点资产**（scrapper 写入的图标/社交图/截图）**不随书签删除而删除**，这是有意的：
  key 由源 URL 哈希得出，多个书签可能指向同一张图，跟着某一个书签删会打断其他书签的引用。
  这类对象是**可再生**的（重抓一次即可），正确的回收手段是桶上按前缀配置的生命周期规则（§6.2），
  而不是在业务代码里做引用计数。

### 3（P1）死代码包裹安全逻辑

`restoreBookmarkLogoAndOg` / `restoreImg` / `uploadImg` / `getLogoUrl` / `displayUrl()`
均无调用方。其中 `restoreImg` 内含完整的 SSRF 防护（DNS pinning、禁自动重定向、
有界读防 OOM），注释还带 F-01/F-05/F-OOM 编号，显然是安全评审产物。
现状是**两套 SSRF 实现只有 scrapper 那套在跑**，死的那套一旦被"复活"极难确认是否仍等效。

### 5（P1）签名 URL 打死缓存

`signWithResize` 默认 1h 有效期，但每次调用都生成新 URL（query 里 `Expires`/`Signature` 每次变），
浏览器与 CDN 永远 cache miss。后果是同一图标每次刷新都回源，且每次回源都触发一次
按次计费的 OSS 图片处理（`image/resize,m_fill`）。首页几十个图标的场景成本与首屏延迟双输。

## 4. 决策：方案 B（切策略边界，保留传输职责）

曾评估的方案 A（API 下发预签名 PUT URL、scrapper 零凭据）能一次性消掉 #1 #4 #6，
但要求 key 在抓取前就定好、而此时尚不知 content-type，需要改成无扩展名 UUID key 并依赖
`site_asset.mime` 还原类型，改动面较大。**本次采用方案 B**，它以约 1 小时的改动量拿下 80% 收益，
且不阻塞将来升级到方案 A。

方案 B 三条：

1. scrapper 的 `upload_bytes` 返回 **object key 而非完整 URL**，契约相应改为 `storageKey`
2. scrapper 换独立 RAM 子账号，Bucket Policy 硬限前缀（配置动作，见 §6）
3. `OSS_PREFIX` 常量改为环境变量，默认值去掉品牌名

## 5. 改动清单

### B 组 —— 边界切割

| 编号 | 文件 | 改动 |
|---|---|---|
| B1 | `scrapper/src/oss.rs` | `OSS_PREFIX` 常量 → `OSS_KEY_PREFIX` 环境变量，默认 `scrapper`（去品牌） |
| B2 | `scrapper/src/oss.rs`、`pipeline.rs`、`main.rs` | `upload_bytes` 返回 key；`OssClient` 不再需要 `base_url` |
| B2 | `contract.rs`、`ScrapeContract.kt` | `Asset` / `Screenshot` 新增 `storageKey`；`storageUrl` 保留为 legacy 只读，不再产出 |
| B3 | `api/utils/OssUtils.kt` | 新增 `signAsset(ref, size)`：裸 key 直签，整条 URL 走存量兼容路径 |
| B4 | `api/.../ScrapeResponseExt.kt` | 四个 URL 派生属性全部走签名，修复存量 403 |
| B4 | `api/.../SiteAssetResolver.kt` | `presentUrl` 改用 `signAsset`，同时支持新 key 与存量 URL |
| B5 | `contract/scrape-response.sample.json` | 同步夹具，三套测试（Rust `contract.rs`、`ScrapeContractTest`、`SiteAssetIngestorTest`）保持绿 |

**兼容策略：** `site_asset.storage_url` 列中的存量数据是完整 URL，新写入是裸 key。
统一入口按"是否以 `http(s)://` 开头"分流，两种形态并存，**无需数据迁移、无需停机**，
也不要求 API 与 scrapper 同时上线。

### C 组 —— 顺带修复的架构问题

| 编号 | 对应发现 | 文件 | 改动 |
|---|---|---|---|
| C1 | #3 | `OssUtils.kt`、`FileType.kt`、`SiteAssetEntity.kt` | 删死代码及仅被其引用的枚举项 |
| C2 | #5 | `OssUtils.kt` | 过期时间按窗口向下取整，窗口内 URL 稳定 |
| C3 | #2 | `BackgroundImageServiceImpl.kt` | 删背景图时一并删 OSS 对象 |
| C4 | #7 | `scrapper/src/oss.rs` | 4xx 鉴权类错误不重试 |
| C5 | #9 | `RedisType.kt` | 移除未使用的 `DEFAULT_BACKGROUND_IMAGES` |

## 6. 需要人工执行的配置（代码无法覆盖）

### 6.1 scrapper 专用 RAM 子账号（对应发现 #4）

新建 RAM 用户，只授予下述策略，然后把它的 AK/SK 换给 scrapper 容器：

```json
{
  "Version": "1",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["oss:PutObject"],
      "Resource": ["acs:oss:*:*:<bucket>/scrapper/*"]
    }
  ]
}
```

要点：只给 `PutObject`，不给 `GetObject`/`DeleteObject`/`ListBucket`；Resource 前缀必须与
`OSS_KEY_PREFIX` 一致。这样即使 scrapper（直面公网、跑 headless Chrome、带 SSRF 面）被攻破，
攻击者也只能往这一个前缀写入，碰不到 `bookmarkify/avatar/**` 等用户数据。

### 6.2 lifecycle 规则（对应发现 #2）

在桶上对 `scrapper/` 前缀配置生命周期规则（建议 180 天过期）。scrapper 产出的对象是
**可再生**的（重新抓一次即可），不是系统的真相来源，因此可以放心过期。
`bookmarkify/avatar/**`、`bookmarkify/bac/**` 是用户数据，**不要**加过期规则。

### 6.3 域名一致性

`OSS_BASE_URL` 在方案 B 下已从 scrapper 移除，不再需要与 API 的
`BOOKMARKIFY_ALIYUN_OSS_DOMAIN` 保持一致。部署时可直接删掉该环境变量。

## 7. 明确延后的项

| 发现 | 为什么延后 |
|---|---|
| #6 两套签名实现 | 方案 B 保留了 scrapper 的上传能力，手写 V1 签名随之保留。真正的解法是方案 A（scrapper 零凭据、删掉整段加解密代码）。在此之前需关注：阿里云正逐步收紧 V1 签名；`Date` 头取本机时钟，容器时钟漂移 >15 分钟会导致**所有上传静默全挂** |
| #8 静态全局状态 | `OssUtils` 的 `companion object` + `lateinit` 使 OSS 逻辑不可注入、不可 mock，直接后果是 API 侧 OSS 相关代码零测试覆盖（对比 scrapper 侧 `oss.rs` 有单测含 RFC 2202 KAT）。改成注入式 Bean 会触及全部调用点，单独排期 |
| #10 key 用源 URL 哈希 | 改用 `contentHash` 做 key 可一次解决去重、对象不可变（可上长 TTL 缓存）、消除覆盖写三件事，但需要配套的数据迁移。建议与方案 A 一起做 |

## 8. 升级到方案 A 的路径

方案 B 已经把"域名归属"和"key 布局"收回 API，方案 A 只剩最后一步：**把凭据也拿掉**。

```
API ──(scrape 请求 + N 个预签名 PUT slot)──► scrapper
                                              │ 字节直传，不经 API
                                              ▼
                                            OSS
scrapper ──(每个 slot 装了哪个 asset)──► API ──► 自己拼域名 + 签 GET
```

届时 `oss.rs` 中的 hmac / sha1 / base64 加解密代码可整段删除，发现 #4 与 #6 一并消失。

另一个独立的小项：`main.rs` 的截图上传更适合直接返回 `image/png` 原始字节，
由调用方决定存不存、存哪 —— 那条路径上 scrapper 完全不需要碰 OSS。
