# AccessToken（浏览器插件访问令牌）设计文档

> 日期：2026-07-28
> 范围：`bookmarkify-web`（设置页新增分类）+ `bookmarkify-api`（新增独立鉴权体系与接口）
> 目标：让用户在设置页生成一个 AccessToken，供官方浏览器插件用来查询"当前网站的标题/图标"等基础信息，且泄露后影响面仅限于这一项能力，不触及账号其他数据。

## 背景

插件需要在用户浏览网页时，向后端请求"这个网站叫什么名字、图标是什么"（用于插件内快速展示/收藏预填）。插件是脱离浏览器 satoken 会话的独立运行环境，需要一种用户可自行签发、管理、撤销的长期凭证。

现有鉴权（`SaTokenConfigure.kt`）用 Sa-Token 的 `StpKit.USER.checkLogin()` 拦截除 `/admin/**` 外的所有路径，凭证是登录会话产生的 `satoken`。若直接复用这套机制给插件签发一个"永不过期的 satoken"，插件泄露等价于泄露用户完整会话——能读写全部书签、改账号信息。因此选择**与 Sa-Token 完全独立的第二套凭证体系**，只为插件开放一个只读接口，权限面从设计上就收窄到最小。

## 总体架构

```
浏览器插件 ──GET /extension/site-info?url=...──► bookmarkify-api
              header: X-Extension-Token: <token>        │
                                                          ├─ ExtensionTokenInterceptor（新增，独立于 StpKit）
                                                          │    校验 token → 查 access_token 表 → 解析出 uid
                                                          │
                                                          └─ ExtensionController
                                                               └─ 复用现有 IApiService.queryWebsiteInfo(url)
                                                                    （即 BookmarkServiceImpl 添加书签时同一条
                                                                     scrapper /scrape 链路，同步返回）

bookmarkify-web 设置页 ──POST /user/access-token────────► bookmarkify-api
     (AccessToken 分类)  GET  /user/access-token           │
     走正常 satoken 会话  DELETE /user/access-token/{id}    └─ AccessTokenController（走现有 StpKit.USER 拦截器）
```

两套接口互不干扰：管理接口走用户已登录的浏览器会话（正常 `satoken` header）；插件调用的接口走新的 `X-Extension-Token` header，两者在 `SaTokenConfigure` 里通过路径前缀（`/user/access-token/**` vs `/extension/**`）区分。

## 数据层

新表 `access_token`：

| 列 | 说明 |
|---|---|
| `id` | 主键，UUID |
| `uid` | 所属用户 |
| `name` | 用户自定义备注，如"Chrome插件" |
| `token_hash` | SHA-256(原始 token)，只存哈希 |
| `token_prefix` | 展示用前缀（如 `bmk_ext_a1b2****`），明文只在创建时返回一次 |
| `last_used_at` | 最近一次校验通过的时间，nullable |
| `create_time` | 创建时间 |

撤销 = 物理删除该行（无需额外的 `revoked` 字段）。不设过期时间——用户主动生成/撤销即可，避免过度设计。

## 鉴权机制：与 Sa-Token 平行的轻量拦截

- 新增 `ExtensionTokenInterceptor`（普通 `HandlerInterceptor`，不是 `SaInterceptor`），只挂在 `/extension/**`
- 请求头 `X-Extension-Token`（区别于 `satoken`，语义上和浏览器会话彻底分开，避免混淆）
- 拦截逻辑：取 header → `SHA-256` 哈希 → 查 `access_token` 表 → 命中则把 `uid` 写入 request attribute 并放行、异步更新 `last_used_at`；未命中抛 `CommonException(ErrorType.E127)` → 由现有 `GlobalExceptionHandler` 统一转成 `Result<T>` 错误响应
- `SaTokenConfigure.kt` 的 `StpKit.USER` 拦截器需要把 `/extension/**` 也加入 `excludePathPatterns`（否则请求会先被"未登录"拦下）
- Controller 内通过 `ExtensionAuthUtils.currentUid()`（从 request attribute 取值）获取当前用户，不经过 `StpKit`/`BaseUtils`

这样泄露该 token 的影响面严格限定在"查网站信息"这一个接口，不触及账号其他能力——对应 Stripe 的 restricted key、GitHub fine-grained PAT 的思路。

## 接口设计

**管理类（走正常 satoken 会话，设置页调用）**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/user/access-token/create` | 创建，body `{name}`，响应含**一次性**明文 token |
| GET | `/user/access-token/list` | 列表（`id`/`name`/`prefix`/`createTime`/`lastUsedAt`，不含明文） |
| POST | `/user/access-token/revoke?id=` | 撤销（校验属于当前用户）——前端 `http` 客户端只封装了 GET/POST，故未用 DELETE |

**插件调用类（走 `ExtensionTokenInterceptor`）**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/extension/site-info?url=` | 内部调用 `IApiService.queryWebsiteInfo(url)`（与添加书签同一条 scrapper 链路），只回 `{title, favicon}`，不落库、不走 `BOOKMARK_LOADING`/WebSocket 异步流程 |

`/extension/site-info` 挂 `@Throttle`（复用现有 `config/throttle/Throttle` 切面）做基础限流；由于该路径下没有 satoken 会话，`ThrottleAspect` 会自动回退到按客户端 IP 限流（现有 `BaseUtils.uid()` 取不到值时的既有 fallback 行为）。

## 安全设计

1. **传输层**：全程只走生产环境已有的 HTTPS（nginx 终止 TLS）；插件侧应拒绝非 https 请求。
2. **Header 而非 URL 参数**：token 放 `X-Extension-Token` header，`url` 查询参数只是目标网址本身——避免 token 出现在 nginx access log / 浏览器历史 / 代理日志里。
3. **只存哈希**：`token_hash` 用 `SHA-256`（token 本身高熵随机，不需要 bcrypt 这类慢哈希）；明文仅创建时返回一次，之后连开发者在库里也看不到。
4. **权限最小化**：token 只能打 `/extension/site-info` 一个接口，与账号完整会话（satoken）完全隔离。
5. **展示与撤销**：设置页只显示 `token_prefix`，用户可随时撤销（对应列表行删除即失效）。
6. **日志卫生**：任何日志/异常上报都不得打印完整 token，只打印前缀（参考现有 `GlobalExceptionHandler.mask()` 对 satoken 的处理方式）。
7. **插件侧存储**（约定，插件仓库外实现）：token 只应存在插件的 background/service worker 上下文（`chrome.storage.local`），不下发给 content script 或页面 DOM，避免被访问页面的 XSS 通过被污染的 content script 窃取。

## 前端（bookmarkify-web）

- 新分类 `AccessToken`，与 `BookmarkManage.vue` / `ShareManage.vue` 同级：`components/setting/AccessTokenManage.vue`
- 接入 `pages/setting.vue`（组件数组新增第 7 项）+ `layouts/setting.vue`（侧边栏 `tabs` 新增一项，`value: 7`）
- UI：列表（name / 前缀 / 创建时间 / 最后使用时间 / 撤销按钮）+ "生成新 Token" 按钮 → 输入备注名 → 调用创建接口 → 一次性展示明文 + 复制按钮 + "仅显示一次，请妥善保存" 提示
- `server/apis/index.ts` 新增 `accessTokenCreate` / `accessTokenList` / `accessTokenRevoke`；`typing/accessToken.ts` 定义对应类型
- i18n：`settingLayout.tabs.accessToken` + `accessTokenManage.*`，覆盖 `zh-CN` / `en` / `ja` / `fr` 四个 locale

## 浏览器插件（仓库外）

不在这四个服务范围内，仅约定契约：

- 请求头：`X-Extension-Token: <token>`
- 接口：`GET {API_BASE}/extension/site-info?url=<当前页面URL>`
- 响应：沿用现有 `Result<T> { code, msg, data, ok }` 包装，`data` 为 `{ title, favicon }`（`favicon` 为 base64 data URL）
