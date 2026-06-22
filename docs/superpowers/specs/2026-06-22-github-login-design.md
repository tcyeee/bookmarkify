# GitHub 登录 + 账号绑定 — 设计文档

日期：2026-06-22
分支：`feat/github-login`

## 目标

为 Bookmarkify 增加 **GitHub 第三方登录** 能力，完整对标现有 Google 集成的两套场景：

1. **登录 / 注册**：欢迎页用 GitHub 账号登录，账户不存在则注册。
2. **账号绑定 / 解绑**：账户设置页将 GitHub 关联到当前已登录账户（严格一对一）。

## 与 Google 实现的关键差异

Google 用 GIS 客户端 SDK，浏览器直接拿到 ID Token（JWT）交给后端校验。
GitHub **没有客户端 SDK**，必须走 OAuth2 **授权码（authorization code）流程**：浏览器只拿到 `code`，由后端用 `code` + `client_secret` 去换 `access_token`，再拉取用户信息。因此前端需要一个回调页承接重定向。

## 架构总览

采用 **弹窗 + 前端回调页** 方案（最贴合现有静态 SPA + satoken 放响应体的架构）：

```
[GitHub 按钮] window.open(authorizeUrl) → GitHub 授权页
   → GitHub 302 回 前端 /auth/github/callback?code=xxx&state=yyy
   → 回调页校验 state（防 CSRF），按意图调后端：
       · 登录场景 → POST /auth/github       → UserSessionInfo（含 satoken）
       · 绑定场景 → POST /user/github/bind   → UserInfoShow
   → 回调页 postMessage({type, ok, ...}) 给主窗口（校验 origin）
   → 主窗口收尾（登录: authStore.postLoginSetup；绑定: 刷新资料）→ 关闭弹窗
```

授权 URL：`https://github.com/login/oauth/authorize?client_id=...&redirect_uri=...&scope=read:user%20user:email&state=...`
- `scope`：`read:user user:email`（需要 `user:email` 才能读到邮箱，含私密主邮箱）。
- `state`：前端生成随机串，存 `sessionStorage`，回调时比对。
- `redirect_uri`：`{siteUrl}/auth/github/callback`。

后端拿 `code` 后：
1. `POST https://github.com/login/oauth/access_token`（带 `client_id` / `client_secret` / `code` / `redirect_uri`，`Accept: application/json`）→ `access_token`。
2. `GET https://api.github.com/user`（`Authorization: Bearer <token>`）→ `id`(数字，稳定唯一) + `login`(用户名) + 可能为空的 `email`。
3. 若 `/user` 的 email 为空，`GET https://api.github.com/user/emails` 取 `primary && verified` 的邮箱。

**出口代理**：复用现有 clash 出口代理配置（与 Google 校验同一套 `googleProxyHost` / `googleProxyPort`），国内服务器直连 GitHub 不稳定。不新增独立的 github 代理配置。

## 后端（bookmarkify-api）

### 数据模型
`UserEntity` 新增两列（对标 `googleId` / `googleEmail`）：
- `githubId: String?`（GitHub 数字 id 的字符串形式，稳定唯一，作关联主键）
- `githubLogin: String?`（GitHub 用户名，仅展示）

> 注意：GitHub 邮箱可能私密/为空，故不把邮箱作为存储主字段；用 `githubLogin` 做展示标识。需要相应数据库迁移（新增两列，可空）。

### 端点
| 方法 | 路径 | 说明 | 返回 |
|---|---|---|---|
| POST | `/auth/github` | 用 code 登录/注册 | `UserSessionInfo` |
| POST | `/user/github/bind` | 绑定到当前账户 | `UserInfoShow` |
| POST | `/user/github/unbind` | 解绑当前账户 | `UserInfoShow` |

- `/auth/github` 放在 `LoginController`（对标 `/auth/google`）。
- `bind` / `unbind` 放在 `UserController`（对标 `/user/google/bind`、`/user/google/unbind`）。

### 登录匹配优先级（`loginByGithub`，对标 `loginByGoogle`）
1. `githubId` 命中 → 登录该账户。
2. 未命中，但 GitHub 返回了**已验证主邮箱**且匹配现有账户 → 自动回填 `githubId` / `githubLogin` 完成关联（兼容老用户）。
3. 都没有 → 注册一个新的正式用户，写入 `githubId` / `githubLogin`（邮箱若有则一并写入账户邮箱字段，若无则留空）。

> 若 GitHub 无可用已验证邮箱，跳过第 2 步，直接走第 3 步注册（账户邮箱为空，仅以 githubLogin 标识）。

### 绑定 / 解绑（对标 bindGoogle/unbindGoogle）
- `bindGithub`：当前账户已绑其他 GitHub → `E114`；该 GitHub 已被他人绑定 → `E113`；否则写入。
- `unbindGithub`：清空 `githubId` / `githubLogin`。

### 新增类型 / 配置
- `Request.kt`：`data class GithubLoginParams(val code: String, val redirectUri: String)`。
- 私有 `data class GithubIdentity(val githubId: String, val login: String, val email: String?)`。
- `ProjectConfig`：`var githubClientId: String = ""`、`var githubClientSecret: String = ""`。
- `application.yml`：
  - `github-client-id: ${BOOKMARKIFY_GITHUB_CLIENT_ID:}`
  - `github-client-secret: ${BOOKMARKIFY_GITHUB_CLIENT_SECRET:}`
  - 未配置 ClientId 则视为关闭 GitHub 登录（端点抛 `E111` 之类的"未配置"错误）。
- 代理沿用 `googleProxyHost` / `googleProxyPort`。

## 前端（bookmarkify-web）

### 新增文件
- `pages/auth/github/callback.vue`
  - 读取 `route.query.code` / `state`；与 `sessionStorage` 中的 `state` + 意图(`login`/`bind`)比对。
  - 调对应 API（`authLoginByGithub` 或 `bindGithub`），结果通过 `window.opener.postMessage(...)` 回传（带固定 `type` 标识），随后 `window.close()`。
  - 极简 UI（"正在登录…/可关闭此窗口"），`definePageMeta({ layout: 'default' })`，**不挂 auth 中间件**（回调页本身就是建立会话的过程）。
- `components/welcome/login/GithubLoginButton.vue`（对标 `GoogleLoginButton.vue`）
  - 生成 `state`、拼授权 URL、`window.open` 弹窗；`window.addEventListener('message')` 接收回调结果（**校验 `event.origin === location.origin`**）。
  - 成功后 `emit('success')`；接进 `WelcomeLoginDialog.vue` 第三方区，放在 Google 按钮旁。
- `components/setting/account/BindGithubModal.vue`（对标 `BindGoogleModal.vue`）
  - 已绑定显示"解绑"，未绑定显示"关联 GitHub" → 弹窗内点击发起 OAuth 弹窗。
  - 成功后更新 `authStore.account`、`emit('success')`；接进 `AccountProfile.vue`（GitHub 绑定行，位置贴着 Google 绑定行）。

### 修改文件
- `server/apis/index.ts`：新增
  - `authLoginByGithub = (params) => http.post<t.UserInfo>('/auth/github', params)`
  - `bindGithub = (code, redirectUri) => http.post<t.UserInfo>('/user/github/bind', { code, redirectUri })`
  - `unbindGithub = () => http.post<t.UserInfo>('/user/github/unbind')`
- `typing/`：`GithubLoginParams` 类型；`UserInfo` 增 `githubLogin?`（用于设置页展示是否已绑定）。
- `nuxt.config.ts`：`runtimeConfig.public.githubClientId = process.env.NUXT_PUBLIC_GITHUB_CLIENT_ID`。
- `.env.example`：新增 `NUXT_PUBLIC_GITHUB_CLIENT_ID`。

## 配置 / 部署准备（操作清单，非代码）

1. 在 GitHub 注册 OAuth App：
   - Homepage：`https://bookmarkify.cc`
   - Authorization callback URL：`https://bookmarkify.cc/auth/github/callback`
   - 本地开发可同 App 加 `http://localhost:3000/auth/github/callback`（GitHub 支持多回调需在 App 设置，或单独建 dev App）。
2. 后端 env：`BOOKMARKIFY_GITHUB_CLIENT_ID`、`BOOKMARKIFY_GITHUB_CLIENT_SECRET`。
3. 前端 env：`NUXT_PUBLIC_GITHUB_CLIENT_ID`（与后端 ClientId 同值）。
4. 数据库迁移：`user` 表新增 `github_id`、`github_login` 两列（可空）。

## 安全 / 错误处理

- **CSRF**：`state` 随机串，回调严格比对，不一致直接报错不调后端。
- **postMessage**：主窗口监听必须校验 `event.origin`，且只认约定 `type` 字段。
- **后端错误**：沿用 http 客户端对 1xx 业务码的统一弹窗，组件不重复弹。
- **无邮箱**：GitHub 未授权/无验证邮箱时，登录/注册/绑定仍可成功，仅以 `githubLogin` 展示，账户邮箱留空。
- **配置缺失**：后端未配 ClientId/Secret 时端点报"未配置"，前端按钮在缺 `githubClientId` 时禁用（对标 Google 的 `!clientId` 禁用逻辑）。

## 不做的事（YAGNI）

- 不实现 GitHub App（区别于 OAuth App）、不拉取仓库等额外 scope。
- 不做账号合并 UI（冲突时按 E113/E114 报错即可）。
- 不新增独立 github 代理配置，复用 google 代理。
- 整页重定向 / 后端全程驱动方案不采用。

## 影响文件清单

**后端**
- `entity/entity/UserEntity.kt`（+2 列）
- `entity/Request.kt`（GithubLoginParams）
- `config/entity/ProjectConfig.kt`（+githubClientId/Secret）
- `src/main/resources/application.yml`（+env 映射）
- `controller/auth/LoginController.kt`（+/auth/github）
- `controller/user/UserController.kt`（+bind/unbind）
- `server/IUserService.kt` + `server/impl/UserServiceImpl.kt`（loginByGithub/bindGithub/unbindGithub + verify 辅助）
- DB 迁移脚本

**前端**
- `pages/auth/github/callback.vue`（新）
- `components/welcome/login/GithubLoginButton.vue`（新）
- `components/setting/account/BindGithubModal.vue`（新）
- `components/welcome/login/WelcomeLoginDialog.vue`（接入按钮）
- `components/setting/account/AccountProfile.vue`（接入绑定行）
- `server/apis/index.ts`（+3 API）
- `typing/`（GithubLoginParams + UserInfo.githubLogin）
- `nuxt.config.ts`、`.env.example`（githubClientId）
