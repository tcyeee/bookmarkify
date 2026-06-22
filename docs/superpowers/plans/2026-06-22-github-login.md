# GitHub 登录 + 账号绑定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Bookmarkify 增加 GitHub 第三方登录/注册与账户设置页的 GitHub 绑定/解绑，完整对标现有 Google 集成。

**Architecture:** 前端用「弹窗 + 前端回调页」承接 GitHub OAuth2 授权码重定向：按钮开 popup → GitHub 跳回 `/auth/github/callback?code&state` → 回调页把 `{code,state}` `postMessage` 回主窗口并关闭 → 主窗口（持有真正的 Pinia/auth 上下文）校验 state 后把 `code` 发后端。后端用 `code`+`client_secret` 换 `access_token`，再拉 `api.github.com/user`(+`/user/emails`)得到身份，按 `githubId`→邮箱→注册 的优先级登录，或对当前账户做一对一绑定。

**Tech Stack:** Kotlin 2.1 + Spring Boot 3.5 + MyBatis-Plus（后端）；Nuxt 4 + Vue 3 + TypeScript + Pinia + Element Plus（前端）。HTTP 出站用 Hutool `HttpUtil`，复用现有 clash 代理。

## Global Constraints

- **无测试框架**：`bookmarkify-api` 与 `bookmarkify-web` 均无测试运行器（见各自 CLAUDE.md「No tests exist」）。本计划不新增测试框架；每个任务的"验证"步骤用**编译/构建通过 + 手动流程核对**代替自动化测试。
- **命名/分层对标 Google**：后端 service 接口在 `server/`，实现 `*ServiceImpl`；DTO 集中在 `entity/Request.kt`/`Response.kt`；错误码在 `config/exception/ErrorType.kt`（E101–E999）。
- **身份主键用 GitHub 数字 id**（稳定唯一），存为字符串 `githubId`；`githubLogin`（用户名）仅展示。GitHub 邮箱可能私密/缺失，不作存储主字段。
- **出站代理复用** `projectConfig.googleProxyHost`/`googleProxyPort`（国内服务器经 clash 出口）。不新增 github 代理配置。
- **OAuth scope** = `read:user user:email`；`redirect_uri` = `${location.origin}/auth/github/callback`，前后端必须一致并与 GitHub OAuth App 配置一致。
- **前端约定**：`<script setup lang="ts">`；Prettier 130 宽、单引号、无分号、bracket same line；DaisyUI 前缀 `cy-`；API 错误吐司由 `http` 客户端统一处理，组件不重复弹；Pinia stores/`@vueuse/core`/Vue 组件由 Nuxt 自动导入，勿手写 import（组件除外，现有代码对子组件仍显式 import）。
- **安全**：`state` 随机串防 CSRF，主窗口校验；`postMessage` 收发两端都校验 `event.origin === location.origin`。

---

## File Structure

**后端（bookmarkify-api）**
- `entity/entity/UserEntity.kt` — 改：+`githubId` / `githubLogin` 两列
- `entity/Response.kt` — 改：`UserInfoShow` +`githubLogin` 暴露
- `entity/dto/UserSessionInfo.kt` — 改：+`githubLogin` 暴露
- `entity/Request.kt` — 改：+`GithubLoginParams(code, redirectUri)`
- `config/exception/ErrorType.kt` — 改：+E116/E117/E118（GitHub 专用文案）
- `config/entity/ProjectConfig.kt` — 改：+`githubClientId` / `githubClientSecret`
- `src/main/resources/application.yml` — 改：+两个 env 映射
- `server/IUserService.kt` — 改：+3 个方法签名
- `server/impl/UserServiceImpl.kt` — 改：+`loginByGithub` / `bindGithub` / `unbindGithub` / `verifyGithubCode`(私有) / `GithubIdentity`(私有 data class)
- `controller/auth/LoginController.kt` — 改：+`POST /auth/github`
- `controller/user/UserController.kt` — 改：+`POST /user/github/bind`、`POST /user/github/unbind`
- DB 迁移：`sys_user` 加 `github_id`、`github_login`（可空）

**前端（bookmarkify-web）**
- `typing/user.ts` — 改：+`GithubLoginParams`
- `typing/setting.ts` — 改：`UserInfo` +`githubLogin?`
- `server/apis/index.ts` — 改：+`authLoginByGithub` / `bindGithub` / `unbindGithub`
- `stores/auth.store.ts` — 改：+`loginWithGithub`
- `nuxt.config.ts` — 改：`runtimeConfig.public.githubClientId`
- `.env.example` — 改：+`NUXT_PUBLIC_GITHUB_CLIENT_ID`
- `composables/useGithubOAuth.ts` — 新：开 popup、收 message、校验 state，返回 `{code, redirectUri}`
- `pages/auth/github/callback.vue` — 新：回调页，postMessage 回主窗口后关闭
- `components/welcome/login/GithubLoginButton.vue` — 新：欢迎页登录按钮
- `components/welcome/login/WelcomeLoginDialog.vue` — 改：接入按钮
- `components/setting/account/BindGithubModal.vue` — 新：账户设置绑定/解绑
- `components/setting/account/AccountProfile.vue` — 改：接入 GitHub 绑定行

---

## Task 1: 后端数据模型 + 身份字段暴露

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/UserEntity.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/dto/UserSessionInfo.kt`
- DB migration (PostgreSQL, schema `bookmarkify`)

**Interfaces:**
- Produces: `UserEntity.githubId: String?`、`UserEntity.githubLogin: String?`；`UserInfoShow.githubLogin: String?`；`UserSessionInfo.githubLogin: String?`

- [ ] **Step 1: UserEntity 加两列**

在 `UserEntity.kt` 的 `googleEmail` 行后插入（紧邻 Google 字段，保持归类）：

```kotlin
    @field:Size(max = 100) @field:Schema(description = "GitHub 唯一标识(数字 id)") var githubId: String? = null,
    @field:Size(max = 100) @field:Schema(description = "GitHub 用户名(仅展示)") var githubLogin: String? = null,
```

- [ ] **Step 2: UserInfoShow 暴露 githubLogin**

`Response.kt` 的 `UserInfoShow`：在 `googleEmail` 属性后加一行属性，并在其伴生构造（`roles = ..., googleEmail = entity.googleEmail`）追加 `githubLogin`。

属性区加：

```kotlin
    @field:Schema(description = "已关联的 GitHub 用户名(未关联为 null)") var githubLogin: String? = null,
```

构造处改为：

```kotlin
        roles = listOf(entity.role.name), googleEmail = entity.googleEmail, githubLogin = entity.githubLogin
```

- [ ] **Step 3: UserSessionInfo 暴露 githubLogin**

`UserSessionInfo.kt`：在 `googleEmail` 属性后加：

```kotlin
    @field:Schema(description = "已关联的 GitHub 用户名(未关联为 null)") var githubLogin: String? = null,
```

并在其 `from`/构造体里（`googleEmail = user.googleEmail` 一行后）追加：

```kotlin
        githubLogin = user.githubLogin,
```

- [ ] **Step 4: DB 迁移**

对生产/开发库执行（字段可空，不影响存量数据）：

```sql
ALTER TABLE bookmarkify.sys_user ADD COLUMN IF NOT EXISTS github_id   varchar(100);
ALTER TABLE bookmarkify.sys_user ADD COLUMN IF NOT EXISTS github_login varchar(100);
```

MyBatis-Plus 默认驼峰转下划线，`githubId`→`github_id`、`githubLogin`→`github_login`，无需显式 `@TableField`（与 `googleId`→`google_id` 同例）。

- [ ] **Step 5: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL（无未解析符号）。

- [ ] **Step 6: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/UserEntity.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Response.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/dto/UserSessionInfo.kt
git commit -m "feat(api): UserEntity 增加 githubId/githubLogin 并对外暴露"
```

---

## Task 2: 后端配置、请求 DTO、错误码

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/config/entity/ProjectConfig.kt`
- Modify: `bookmarkify-api/src/main/resources/application.yml`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Request.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/config/exception/ErrorType.kt`

**Interfaces:**
- Produces: `projectConfig.githubClientId: String`、`projectConfig.githubClientSecret: String`；`GithubLoginParams(code: String, redirectUri: String)`；错误码 `ErrorType.E116`/`E117`/`E118`

- [ ] **Step 1: ProjectConfig 加字段**

`ProjectConfig.kt` 在 `googleProxyPort` 行后（`)` 之前）加：

```kotlin
    var githubClientId: String = "",          // GitHub 登录 OAuth App Client ID
    var githubClientSecret: String = "",      // GitHub 登录 OAuth App Client Secret（用 code 换 access_token）
```

- [ ] **Step 2: application.yml 加 env 映射**

在 `google-proxy-port` 行后（同 `bookmarkify.config` 缩进层）加：

```yaml
    github-client-id: ${BOOKMARKIFY_GITHUB_CLIENT_ID:}  # GitHub 登录 OAuth App Client ID，未配置则关闭 GitHub 登录
    github-client-secret: ${BOOKMARKIFY_GITHUB_CLIENT_SECRET:}  # GitHub 登录 OAuth App Client Secret
```

- [ ] **Step 3: Request.kt 加 DTO**

在 `GoogleLoginParams` 那一行后加：

```kotlin
data class GithubLoginParams(val code: String, val redirectUri: String)  // GitHub OAuth 授权码 + 回调地址(换 token 时需与授权请求一致)
```

- [ ] **Step 4: ErrorType 加 GitHub 文案**

`ErrorType.kt` 在 `E115` 行后加（E115「解绑后将无法登录…」文案足够通用，GitHub 解绑复用它，不新增）：

```kotlin
    E116("GitHub 登录验证失败,请重试"),
    E117("该 GitHub 账号已被其他账户关联"),
    E118("当前账户已关联 GitHub，请先解绑"),
```

> 若 E116/E117/E118 已被占用，顺延到下一个未用编号，并在后续 Task 3 引用处同步改名。

- [ ] **Step 5: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/config/entity/ProjectConfig.kt \
        bookmarkify-api/src/main/resources/application.yml \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/Request.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/config/exception/ErrorType.kt
git commit -m "feat(api): GitHub 登录配置/请求 DTO/错误码"
```

---

## Task 3: 后端 Service —— code 换身份 + 登录/绑定/解绑

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IUserService.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/UserServiceImpl.kt`

**Interfaces:**
- Consumes: `GithubLoginParams`（Task 2）、`projectConfig.githubClientId/Secret`（Task 2）、`UserEntity.githubId/githubLogin`（Task 1）、`ErrorType.E116/E117/E118`（Task 2）、现有 `createVerifiedUser`、`StpKit.USER`、`updateById`、`ktQuery`/`ktUpdate`、`UserSessionInfo`、`UserInfoShow`
- Produces: `loginByGithub(params): UserSessionInfo`、`bindGithub(uid, params): UserInfoShow`、`unbindGithub(uid): UserInfoShow`

- [ ] **Step 1: IUserService 加签名**

`IUserService.kt` 在 `unbindGoogle` 签名后加（并确保 `import top.tcyeee.bookmarkify.entity.GithubLoginParams`）：

```kotlin
    /**
     * 用 GitHub OAuth 授权码登录(GitHub 账号不存在则注册)
     * @param params 含 GitHub 授权码 code 与回调地址 redirectUri
     */
    fun loginByGithub(params: GithubLoginParams): UserSessionInfo

    /** 关联 GitHub 到当前已登录账户(严格一对一) */
    fun bindGithub(uid: String, params: GithubLoginParams): UserInfoShow

    /** 解绑当前账户的 GitHub 关联(带安全检查,无其他登录凭证则拒绝) */
    fun unbindGithub(uid: String): UserInfoShow
```

- [ ] **Step 2: UserServiceImpl 加 import**

确保 `UserServiceImpl.kt` 顶部含：

```kotlin
import top.tcyeee.bookmarkify.entity.GithubLoginParams
```

（`HttpUtil`、`JSONUtil`、`CommonException`、`ErrorType` 已被 Google 代码引入，无需重复。）

- [ ] **Step 3: 实现 loginByGithub（紧贴 loginByGoogle 之后）**

```kotlin
    /**
     * 用 GitHub 授权码登录,匹配优先级:
     * 1. github_id 命中 => 登录该账户
     * 2. 未命中且 GitHub 有已验证主邮箱 => 按邮箱查现有账户,命中则回填 github_id/github_login
     * 3. 都没有 => 注册新用户(有邮箱则用之,无邮箱则用占位邮箱)并写入 github_id/github_login
     */
    @Transactional
    override fun loginByGithub(params: GithubLoginParams): UserSessionInfo {
        val identity = verifyGithubCode(params)

        val userEntity = ktQuery().eq(UserEntity::githubId, identity.githubId).one()
            ?: identity.email?.let { mail ->
                ktQuery().eq(UserEntity::email, mail).one()?.also {
                    it.githubId = identity.githubId
                    it.githubLogin = identity.login
                    updateById(it)
                }
            }
            ?: createVerifiedUser(identity.email ?: "github_${identity.githubId}@users.noreply.github.com").also {
                it.githubId = identity.githubId
                it.githubLogin = identity.login
                // 占位邮箱不作为真实可登录邮箱:无真实邮箱时清空 email,仅以 github 身份标识
                if (identity.email == null) it.email = null
                updateById(it)
            }

        if (StpKit.USER.isLogin) StpKit.USER.logout()
        StpKit.USER.login(userEntity.id, true)
        return userEntity.authVO(StpKit.USER.tokenValue).writeToSession()
    }
```

> 说明：`createVerifiedUser` 需要邮箱参数（与 Google 路径一致）。无真实邮箱时先传占位邮箱建用户、随即把 `email` 置空，避免占位邮箱污染邮箱登录/注销校验。

- [ ] **Step 4: 实现 bindGithub / unbindGithub（紧贴 unbindGoogle 之后）**

```kotlin
    /**
     * 关联 GitHub 到当前已登录账户(严格一对一):
     * - 当前账户已关联其他 GitHub => E118
     * - 该 GitHub 已被其他账户关联 => E117
     */
    @Transactional
    override fun bindGithub(uid: String, params: GithubLoginParams): UserInfoShow {
        val identity = verifyGithubCode(params)
        val user = getById(uid) ?: throw CommonException(ErrorType.E215)

        if (!user.githubId.isNullOrBlank()) throw CommonException(ErrorType.E118)

        val occupied = ktQuery().eq(UserEntity::githubId, identity.githubId).one()
        if (occupied != null) throw CommonException(ErrorType.E117)

        user.githubId = identity.githubId
        user.githubLogin = identity.login
        updateById(user)
        return UserInfoShow(user, user.avatarUrlWithSign())
    }

    /** 解绑当前账户的 GitHub 关联。解绑后若无密码且无可登录邮箱 => E115。 */
    @Transactional
    override fun unbindGithub(uid: String): UserInfoShow {
        val user = getById(uid) ?: throw CommonException(ErrorType.E215)
        if (user.githubId.isNullOrBlank()) return UserInfoShow(user, user.avatarUrlWithSign())

        val hasOtherCredential = !user.password.isNullOrBlank() || !user.email.isNullOrBlank()
        if (!hasOtherCredential) throw CommonException(ErrorType.E115)

        user.githubId = null
        user.githubLogin = null
        ktUpdate()
            .set(UserEntity::githubId, null)
            .set(UserEntity::githubLogin, null)
            .eq(UserEntity::id, uid)
            .update()
        return UserInfoShow(user, user.avatarUrlWithSign())
    }
```

- [ ] **Step 5: 实现 verifyGithubCode + GithubIdentity（紧贴 verifyGoogleIdToken / GoogleIdentity 之后）**

```kotlin
    /**
     * 用授权码换 access_token,再拉取 GitHub 用户身份(数字 id + login + 已验证主邮箱)。
     * 出站经配置的 HTTP 代理(复用 google 代理),与 Google 校验同一出口。
     */
    private fun verifyGithubCode(params: GithubLoginParams): GithubIdentity {
        val clientId = projectConfig.githubClientId
        val clientSecret = projectConfig.githubClientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) throw CommonException(ErrorType.E116, "服务端未配置 GitHub OAuth")
        if (params.code.isBlank()) throw CommonException(ErrorType.E116)

        fun <T> withProxy(req: cn.hutool.http.HttpRequest): cn.hutool.http.HttpRequest =
            req.apply {
                if (projectConfig.googleProxyHost.isNotBlank() && projectConfig.googleProxyPort > 0) {
                    setHttpProxy(projectConfig.googleProxyHost, projectConfig.googleProxyPort)
                }
            }

        // 1. code -> access_token
        val tokenResp = runCatching {
            withProxy<Unit>(
                HttpUtil.createPost("https://github.com/login/oauth/access_token")
                    .header("Accept", "application/json")
                    .form("client_id", clientId)
                    .form("client_secret", clientSecret)
                    .form("code", params.code)
                    .form("redirect_uri", params.redirectUri)
                    .timeout(8000),
            ).execute()
        }.getOrElse { throw CommonException(ErrorType.E116, "无法连接 GitHub") }
        if (!tokenResp.isOk) throw CommonException(ErrorType.E116)
        val accessToken = JSONUtil.parseObj(tokenResp.body()).getStr("access_token")
        if (accessToken.isNullOrBlank()) throw CommonException(ErrorType.E116, "未获取到 GitHub 令牌")

        // 2. access_token -> 用户信息
        val userResp = runCatching {
            withProxy<Unit>(
                HttpUtil.createGet("https://api.github.com/user")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Accept", "application/vnd.github+json")
                    .timeout(8000),
            ).execute()
        }.getOrElse { throw CommonException(ErrorType.E116, "无法获取 GitHub 用户信息") }
        if (!userResp.isOk) throw CommonException(ErrorType.E116)
        val userObj = JSONUtil.parseObj(userResp.body())
        val githubId = userObj.getLong("id")?.toString()
        val login = userObj.getStr("login")?.trim()
        if (githubId.isNullOrBlank() || login.isNullOrBlank()) throw CommonException(ErrorType.E116, "未获取到 GitHub 标识")

        // 3. 主邮箱(可能私密),取已验证的 primary;失败则视为无邮箱
        var email = userObj.getStr("email")?.trim()?.lowercase()
        if (email.isNullOrBlank()) {
            email = runCatching {
                val emailsResp = withProxy<Unit>(
                    HttpUtil.createGet("https://api.github.com/user/emails")
                        .header("Authorization", "Bearer $accessToken")
                        .header("Accept", "application/vnd.github+json")
                        .timeout(8000),
                ).execute()
                if (!emailsResp.isOk) null
                else JSONUtil.parseArray(emailsResp.body())
                    .map { JSONUtil.parseObj(it) }
                    .firstOrNull { it.getBool("primary", false) && it.getBool("verified", false) }
                    ?.getStr("email")?.trim()?.lowercase()
            }.getOrNull()
        }

        return GithubIdentity(githubId = githubId, login = login, email = email?.ifBlank { null })
    }

    /** GitHub 身份(email 可能为 null) */
    private data class GithubIdentity(val githubId: String, val login: String, val email: String?)
```

> 校验 `cn.hutool.http.HttpRequest` 的实际包路径与现有 `HttpUtil.createGet` 返回类型一致（Google 代码用的是同一 Hutool）。若 `setHttpProxy` 的接收者类型不同，把 `withProxy` 内联进每个 `createGet/createPost` 链（与 Google 写法一致），删掉 `withProxy` 辅助函数。

- [ ] **Step 6: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL。若报 `HttpRequest` 类型/`setHttpProxy` 不匹配，按 Step 5 注释内联代理设置后重试。

- [ ] **Step 7: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/IUserService.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/server/impl/UserServiceImpl.kt
git commit -m "feat(api): GitHub 登录/绑定/解绑 service + code 换身份"
```

---

## Task 4: 后端 Controller 端点

**Files:**
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/auth/LoginController.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/user/UserController.kt`

**Interfaces:**
- Consumes: `userService.loginByGithub/bindGithub/unbindGithub`（Task 3）、`GithubLoginParams`（Task 2）
- Produces: HTTP `POST /auth/github`、`POST /user/github/bind`、`POST /user/github/unbind`

- [ ] **Step 1: LoginController 加 /auth/github**

加 import `import top.tcyeee.bookmarkify.entity.GithubLoginParams`，并在 `loginByGoogle` 方法后加：

```kotlin
    @Throttle(byIp = true)
    @SaIgnore
    @PostMapping("/github")
    @Operation(summary = "用 GitHub 授权码登录（GitHub 账号不存在则注册）")
    fun loginByGithub(@RequestBody params: GithubLoginParams): UserSessionInfo = userService.loginByGithub(params)
```

- [ ] **Step 2: UserController 加 bind/unbind**

加 import `import top.tcyeee.bookmarkify.entity.GithubLoginParams`，并在 `unbindGoogle` 方法后加：

```kotlin
    @PostMapping("github/bind")
    @Operation(summary = "关联 GitHub 账号到当前账户")
    fun bindGithub(@RequestBody params: GithubLoginParams): UserInfoShow =
        userService.bindGithub(BaseUtils.uid(), params)

    @PostMapping("github/unbind")
    @Operation(summary = "解绑当前账户的 GitHub 关联")
    fun unbindGithub(): UserInfoShow = userService.unbindGithub(BaseUtils.uid())
```

- [ ] **Step 3: 编译验证**

Run: `cd bookmarkify-api && ./gradlew compileKotlin -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 运行期冒烟（可选，需本地配好 OAuth App + 库迁移）**

Run: `cd bookmarkify-api && ./gradlew bootRun --args='--spring.profiles.active=dev'`
未配 `BOOKMARKIFY_GITHUB_CLIENT_ID/SECRET` 时，对 `POST /auth/github` 传任意 code 应返回业务错误 E116「服务端未配置 GitHub OAuth」而非 500，确认连通正常即可停。

- [ ] **Step 5: Commit**

```bash
git add bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/auth/LoginController.kt \
        bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/controller/user/UserController.kt
git commit -m "feat(api): 暴露 /auth/github 与 /user/github/{bind,unbind}"
```

---

## Task 5: 前端类型、API、auth store、配置

**Files:**
- Modify: `bookmarkify-web/typing/user.ts`
- Modify: `bookmarkify-web/typing/setting.ts`
- Modify: `bookmarkify-web/server/apis/index.ts`
- Modify: `bookmarkify-web/stores/auth.store.ts`
- Modify: `bookmarkify-web/nuxt.config.ts`
- Modify: `bookmarkify-web/.env.example`

**Interfaces:**
- Produces: `t.GithubLoginParams`、`UserInfo.githubLogin?`、`authLoginByGithub(params)`、`bindGithub(code, redirectUri)`、`unbindGithub()`、`authStore.loginWithGithub(code, redirectUri)`、`config.public.githubClientId`

- [ ] **Step 1: 类型**

`typing/user.ts` 在 `GoogleLoginParams` 接口后加：

```ts
export interface GithubLoginParams {
  code: string
  redirectUri: string
}
```

`typing/setting.ts` 的 `UserInfo` 在 `googleEmail` 行后加：

```ts
  githubLogin?: string | null // 已关联的 GitHub 用户名（未关联为 null）
```

- [ ] **Step 2: API**

`server/apis/index.ts` 在 `unbindGoogle` 行后加：

```ts
export const authLoginByGithub = (params: t.GithubLoginParams) => http.post<t.UserInfo>('/auth/github', params)
export const bindGithub = (code: string, redirectUri: string) => http.post<t.UserInfo>('/user/github/bind', { code, redirectUri })
export const unbindGithub = () => http.post<t.UserInfo>('/user/github/unbind')
```

- [ ] **Step 3: auth store**

`stores/auth.store.ts`：在顶部 import 里把 `authLoginByGithub` 加进 `@api` 解构；在 `loginWithGoogle` action 后加：

```ts
    async loginWithGithub(code: string, redirectUri: string): Promise<UserInfo> {
      try {
        // GitHub 授权码登录/注册，成功后合并到当前账号信息
        const result = await authLoginByGithub({ code, redirectUri })
        this.account = { ...this.account, ...result }
        if (import.meta.client) useNuxtApp().$track('login-github')
        return result
      } catch (err: any) {
        return Promise.reject(err)
      }
    },
```

- [ ] **Step 4: runtimeConfig + env**

`nuxt.config.ts` 的 `runtimeConfig.public` 在 `googleClientId` 行后加：

```ts
      githubClientId: process.env.NUXT_PUBLIC_GITHUB_CLIENT_ID,
```

`.env.example` 在 Google 那两行后加：

```bash
# GitHub 登录 OAuth App Client ID（留空则不显示 GitHub 登录按钮）
NUXT_PUBLIC_GITHUB_CLIENT_ID=''
```

- [ ] **Step 5: 构建验证**

Run: `cd bookmarkify-web && pnpm build`
Expected: 构建成功（类型检查通过，无未解析 import）。

- [ ] **Step 6: Commit**

```bash
git add bookmarkify-web/typing/user.ts bookmarkify-web/typing/setting.ts \
        bookmarkify-web/server/apis/index.ts bookmarkify-web/stores/auth.store.ts \
        bookmarkify-web/nuxt.config.ts bookmarkify-web/.env.example
git commit -m "feat(web): GitHub 登录类型/API/auth store/配置"
```

---

## Task 6: 前端 OAuth 弹窗 composable + 回调页

**Files:**
- Create: `bookmarkify-web/composables/useGithubOAuth.ts`
- Create: `bookmarkify-web/pages/auth/github/callback.vue`

**Interfaces:**
- Consumes: `config.public.githubClientId`（Task 5）
- Produces: `useGithubOAuth()` → `{ githubClientId: string, requestGithubCode(): Promise<{ code: string; redirectUri: string }> }`；回调页路由 `/auth/github/callback`
- 约定 message 形状：`{ source: 'bookmarkify-github-oauth', code?: string, state?: string, error?: string }`

- [ ] **Step 1: composable**

```ts
// composables/useGithubOAuth.ts
// 开 GitHub 授权弹窗，等回调页 postMessage 回 code，校验 state 与 origin 后 resolve。
// 调用方（按钮/绑定弹窗）拿到 code 后自行决定走登录还是绑定 API。
const MSG_SOURCE = 'bookmarkify-github-oauth'

export function useGithubOAuth() {
  const config = useRuntimeConfig()
  const githubClientId = (config.public.githubClientId as string | undefined) || ''

  function requestGithubCode(): Promise<{ code: string; redirectUri: string }> {
    return new Promise((resolve, reject) => {
      if (!import.meta.client) return reject(new Error('仅客户端可用'))
      if (!githubClientId) return reject(new Error('未配置 GitHub ClientId'))

      const redirectUri = `${location.origin}/auth/github/callback`
      const state = Math.random().toString(36).slice(2) + Date.now().toString(36)
      const url =
        `https://github.com/login/oauth/authorize?client_id=${encodeURIComponent(githubClientId)}` +
        `&redirect_uri=${encodeURIComponent(redirectUri)}` +
        `&scope=${encodeURIComponent('read:user user:email')}` +
        `&state=${encodeURIComponent(state)}`

      const popup = window.open(url, 'github-oauth', 'width=600,height=720')
      if (!popup) return reject(new Error('弹窗被拦截，请允许弹出窗口'))

      let settled = false
      const cleanup = () => {
        settled = true
        window.removeEventListener('message', onMessage)
        clearInterval(timer)
      }

      function onMessage(e: MessageEvent) {
        if (e.origin !== location.origin) return
        const d = e.data
        if (!d || d.source !== MSG_SOURCE) return
        cleanup()
        if (d.error) return reject(new Error(d.error))
        if (d.state !== state) return reject(new Error('state 校验失败'))
        if (!d.code) return reject(new Error('未获取到授权码'))
        resolve({ code: d.code, redirectUri })
      }
      window.addEventListener('message', onMessage)

      // 用户手动关闭弹窗 => 视为取消
      const timer = setInterval(() => {
        if (settled) return
        if (popup.closed) {
          cleanup()
          reject(new Error('已取消 GitHub 授权'))
        }
      }, 500)
    })
  }

  return { githubClientId, requestGithubCode }
}
```

- [ ] **Step 2: 回调页**

```vue
<!-- pages/auth/github/callback.vue -->
<template>
  <div class="flex h-screen w-screen items-center justify-center text-white/70">
    <p>{{ message }}</p>
  </div>
</template>

<script lang="ts" setup>
// GitHub OAuth 回调承接页：仅把 code/state 回传给打开它的主窗口后自关，不调任何业务接口。
definePageMeta({ layout: 'default' })

const message = ref('正在完成 GitHub 授权…')
const route = useRoute()

onMounted(() => {
  const payload = {
    source: 'bookmarkify-github-oauth',
    code: (route.query.code as string) || '',
    state: (route.query.state as string) || '',
    error: (route.query.error_description as string) || (route.query.error as string) || '',
  }
  if (window.opener) {
    window.opener.postMessage(payload, location.origin)
    message.value = '授权完成，正在关闭…'
    setTimeout(() => window.close(), 300)
  } else {
    // 非弹窗场景（用户直接访问）兜底回首页
    message.value = '授权异常，即将返回首页'
    setTimeout(() => navigateTo('/'), 1200)
  }
})
</script>
```

> 回调页不能挂 `middleware: 'auth'`（它本身处于建立会话过程中）。`layout: 'default'` 是 pass-through，不会触发 launch 背景与数据加载。

- [ ] **Step 3: 构建验证**

Run: `cd bookmarkify-web && pnpm build`
Expected: 构建成功，且 `.output` 中生成 `/auth/github/callback` 路由（`pnpm dev` 下访问 `http://localhost:3000/auth/github/callback?code=x&state=y` 应显示"授权完成"文案）。

- [ ] **Step 4: Commit**

```bash
git add bookmarkify-web/composables/useGithubOAuth.ts bookmarkify-web/pages/auth/github/callback.vue
git commit -m "feat(web): GitHub OAuth 弹窗 composable 与回调承接页"
```

---

## Task 7: 欢迎页 GitHub 登录按钮

**Files:**
- Create: `bookmarkify-web/components/welcome/login/GithubLoginButton.vue`
- Modify: `bookmarkify-web/components/welcome/login/WelcomeLoginDialog.vue`

**Interfaces:**
- Consumes: `useGithubOAuth().requestGithubCode`（Task 6）、`authStore.loginWithGithub`（Task 5）
- Produces: `<GithubLoginButton @success />`

- [ ] **Step 1: 按钮组件**

```vue
<!-- components/welcome/login/GithubLoginButton.vue -->
<template>
  <!-- 未配置 ClientId 时不渲染，避免出现点不动的按钮 -->
  <button
    v-if="githubClientId"
    type="button"
    class="cy-btn cy-btn-neutral w-full max-w-[320px] gap-2"
    :disabled="loading"
    @click="onClick">
    <Icon icon="mdi:github" class="size-5" />
    <span>{{ loading ? '授权中…' : '使用 GitHub 登录' }}</span>
  </button>
</template>

<script lang="ts" setup>
import { useAuthStore } from '@stores/auth.store'

const emit = defineEmits<{ (e: 'success'): void }>()

const authStore = useAuthStore()
const { githubClientId, requestGithubCode } = useGithubOAuth()
const loading = ref(false)

async function onClick() {
  if (loading.value) return
  loading.value = true
  try {
    const { code, redirectUri } = await requestGithubCode()
    await authStore.loginWithGithub(code, redirectUri)
    emit('success')
  } catch (err: any) {
    // 用户取消等本地错误在这里提示；后端 1xx 业务错误由 http 客户端统一弹窗
    if (err?.message && err.message !== '已取消 GitHub 授权') ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}
</script>
```

> `Icon` 来自 `@iconify/vue`，项目其他地方已直接使用（见 `WelcomeLoginDialog.vue` 的 `<Icon icon="memory:bookmark" />`），无需手动 import。

- [ ] **Step 2: 接进登录弹窗**

`WelcomeLoginDialog.vue`：`<script setup>` 顶部加 `import GithubLoginButton from './GithubLoginButton.vue'`；模板里第三方区把 Google 按钮那块改为两个按钮纵向排列：

```vue
    <div class="mt-4 flex flex-col items-center gap-3">
      <GoogleLoginButton @success="onSuccess" />
      <GithubLoginButton @success="onSuccess" />
    </div>
```

- [ ] **Step 3: 构建验证**

Run: `cd bookmarkify-web && pnpm build`
Expected: 构建成功。`pnpm dev` 下打开 `/welcome` 登录弹窗（需设置 `NUXT_PUBLIC_GITHUB_CLIENT_ID` 才显示按钮），点击应弹出 GitHub 授权窗口。

- [ ] **Step 4: Commit**

```bash
git add bookmarkify-web/components/welcome/login/GithubLoginButton.vue \
        bookmarkify-web/components/welcome/login/WelcomeLoginDialog.vue
git commit -m "feat(web): 欢迎页 GitHub 登录按钮"
```

---

## Task 8: 账户设置 GitHub 绑定/解绑

**Files:**
- Create: `bookmarkify-web/components/setting/account/BindGithubModal.vue`
- Modify: `bookmarkify-web/components/setting/account/AccountProfile.vue`

**Interfaces:**
- Consumes: `useGithubOAuth().requestGithubCode`（Task 6）、`bindGithub`/`unbindGithub`（Task 5）、`authStore.account`、`UserInfo.githubLogin`（Task 5）
- Produces: `<BindGithubModal :github-login @success />`

- [ ] **Step 1: 绑定组件**

```vue
<!-- components/setting/account/BindGithubModal.vue -->
<template>
  <div class="flex items-center gap-2">
    <button
      v-if="props.githubLogin"
      class="cy-btn cy-btn-ghost h-10 px-4 min-w-[104px]"
      :disabled="loading || disabled"
      @click="handleUnbind">
      <span v-if="loading">处理中...</span>
      <span v-else>解绑</span>
    </button>
    <button
      v-else
      class="cy-btn cy-btn-ghost h-10 px-4 min-w-[104px]"
      :disabled="loading || disabled || !githubClientId"
      @click="handleBind">
      <span>{{ loading ? '授权中...' : '关联 GitHub' }}</span>
    </button>
  </div>
</template>

<script lang="ts" setup>
import { bindGithub, unbindGithub } from '@api'
import { useAuthStore } from '@stores/auth.store'

const props = defineProps<{ githubLogin?: string | null; disabled?: boolean }>()
const emit = defineEmits<{ (e: 'success'): void }>()

const authStore = useAuthStore()
const { githubClientId, requestGithubCode } = useGithubOAuth()
const loading = ref(false)

async function handleBind() {
  if (loading.value) return
  loading.value = true
  try {
    const { code, redirectUri } = await requestGithubCode()
    const result = await bindGithub(code, redirectUri)
    authStore.account = { ...authStore.account, ...result } as any
    ElNotification.success({ message: 'GitHub 关联成功' })
    emit('success')
  } catch (err: any) {
    if (err?.message && err.message !== '已取消 GitHub 授权') ElMessage.error(err.message)
  } finally {
    loading.value = false
  }
}

async function handleUnbind() {
  try {
    await ElMessageBox.confirm('解绑后将无法用此 GitHub 账号登录，确定解绑吗？', '解绑 GitHub', {
      confirmButtonText: '解绑',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户取消
  }
  loading.value = true
  try {
    const result = await unbindGithub()
    authStore.account = { ...authStore.account, ...result } as any
    ElNotification.success({ message: '已解绑 GitHub' })
    emit('success')
  } catch {
    // http 客户端已统一提示
  } finally {
    loading.value = false
  }
}
</script>
```

> GitHub 绑定走授权码弹窗、无需像 Google 那样在 `<dialog>` 内渲染 SDK 按钮，故组件比 `BindGoogleModal` 更简单，无 `<dialog>`。

- [ ] **Step 2: 接进 AccountProfile**

`AccountProfile.vue`：
1. `<script setup>` 顶部加 `import BindGithubModal from './BindGithubModal.vue'`。
2. 在 `googleEmail` computed 后加：

```ts
const githubLogin = computed(() => account.value?.githubLogin ?? null)
```

3. 模板里在 Google 账号那个 `<div>…</div>` 整行块之后，复制一份结构改为 GitHub（图标用 `<Icon icon="mdi:github" class="size-8" />` 替换 Google 的 inline svg；副标题显示 `githubLogin || '未关联'`；右侧放 `<BindGithubModal :github-login="githubLogin" :disabled="saving" />`）：

```vue
        <div class="flex items-center justify-between rounded-xl ...同 Google 行的外层 class...">
          <div class="flex items-center gap-3">
            <Icon icon="mdi:github" class="size-8 text-slate-800 dark:text-slate-100" />
            <div>
              <div class="font-medium">GitHub 账号</div>
              <div class="text-sm text-slate-500 dark:text-slate-400">{{ githubLogin || '未关联' }}</div>
            </div>
          </div>
          <div class="flex items-center gap-3">
            <BindGithubModal :github-login="githubLogin" :disabled="saving" />
          </div>
        </div>
```

> 外层 `<div>` 的 class 直接照抄同文件 Google 账号行的外层容器 class，保证视觉一致。

- [ ] **Step 3: 构建验证**

Run: `cd bookmarkify-web && pnpm build`
Expected: 构建成功。`pnpm dev` 下进入账户设置页应看到「GitHub 账号」行；已登录用户点「关联 GitHub」弹授权窗，成功后该行显示用户名并变为「解绑」。

- [ ] **Step 4: Commit**

```bash
git add bookmarkify-web/components/setting/account/BindGithubModal.vue \
        bookmarkify-web/components/setting/account/AccountProfile.vue
git commit -m "feat(web): 账户设置 GitHub 绑定/解绑"
```

---

## Task 9: 端到端联调与文档

**Files:**
- Modify: `bookmarkify-web/.env`（本地，不提交）、后端本地 env

**Interfaces:** 无新增产物，纯验证 + 收尾。

- [ ] **Step 1: 注册 GitHub OAuth App**

GitHub → Settings → Developer settings → OAuth Apps → New：
- Homepage URL：`http://localhost:3000`（本地）
- Authorization callback URL：`http://localhost:3000/auth/github/callback`
拿到 Client ID / 生成 Client Secret。

- [ ] **Step 2: 配置 env**

- 后端：`BOOKMARKIFY_GITHUB_CLIENT_ID`、`BOOKMARKIFY_GITHUB_CLIENT_SECRET`
- 前端 `.env`：`NUXT_PUBLIC_GITHUB_CLIENT_ID`（同 Client ID）
- 确认本地库已执行 Task 1 Step 4 的迁移。

- [ ] **Step 3: 起服务**

后端：`cd bookmarkify-api && ./gradlew bootRun --args='--spring.profiles.active=dev'`
前端：`cd bookmarkify-web && pnpm dev`（注意 scrapper 占 3000 时需先停掉）

- [ ] **Step 4: 手动核对四条路径**

1. **新用户登录**：未登录态 `/welcome` 点「使用 GitHub 登录」→ 授权 → 弹窗自关 → 主站进入已登录态（首页加载书签）。
2. **老用户邮箱自动关联**：用与某已注册邮箱一致的 GitHub（已验证主邮箱）登录 → 登进该老账户，`sys_user.github_id` 被回填。
3. **绑定**：已登录用户在账户设置点「关联 GitHub」→ 授权 → 行显示用户名 + 变「解绑」。
4. **解绑**：点「解绑」确认 → 行回到「未关联」；若账户既无密码又无邮箱，后端应拒绝并提示 E115。

- [ ] **Step 5: 更新生产部署清单（写进 PR 描述，不写死进仓库）**

提醒：生产 GitHub OAuth App 的 callback 需加 `https://bookmarkify.cc/auth/github/callback`；生产 env 配 `BOOKMARKIFY_GITHUB_CLIENT_ID/SECRET`（后端）与 `NUXT_PUBLIC_GITHUB_CLIENT_ID`（web 部署工作流，与 Google 同位置）；生产库执行 `sys_user` 两列迁移。

- [ ] **Step 6: 无新代码改动则跳过 commit**

如本任务仅做配置与联调，无需提交；如调试中修了 bug，按所属 Task 归类提交。

---

## Self-Review（已核对）

- **Spec 覆盖**：登录(Task 3/4/5/7) + 绑定/解绑(Task 3/4/5/8) + 弹窗回调架构(Task 6) + 数据模型(Task 1) + 配置(Task 2/5) + 联调与部署清单(Task 9) 均有对应任务。
- **类型一致**：后端 `loginByGithub`/`bindGithub`/`unbindGithub`、`GithubLoginParams(code, redirectUri)`、`GithubIdentity(githubId, login, email?)`、`githubId`/`githubLogin` 列名贯穿一致；前端 `requestGithubCode()→{code,redirectUri}`、`loginWithGithub(code, redirectUri)`、`bindGithub(code, redirectUri)`、message `source:'bookmarkify-github-oauth'` 首尾一致。
- **占位符**：无 TBD/TODO；每个代码步骤含完整代码。
- **已知需实测校正点**：① Hutool `HttpRequest`/`setHttpProxy` 实际类型（Task 3 Step 5 注释给了内联回退）；② `ErrorType` E116–E118 编号是否空闲（Task 2 Step 4 给了顺延说明）；③ `AccountProfile` Google 行的外层容器 class 需照抄（Task 8 Step 2）。
