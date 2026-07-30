# AccessToken 设计与接口文档

本文档描述 Bookmarkify 的「插件访问令牌」(AccessToken) 体系：它是什么、为什么要与登录会话分离、数据怎么存、以及第三方（浏览器插件 / 自动化脚本 / AI 助手）如何调用相关接口。

代码中多处注释写了"详见根目录 ACCESS_TOKEN_DESIGN.md"，指的就是本文件。

## 为什么需要它

`bookmarkify-web` 的正常登录态是 Sa-Token 会话（`satoken` 请求头，见 `bookmarkify-web/CLAUDE.md`），只在浏览器里有效，无法交给第三方脚本长期持有——泄露会话等价于泄露整个账号。

AccessToken 解决的是"第三方只读集成"这一个具体场景：用户在设置页生成一个长期有效、与登录会话完全独立的令牌，交给浏览器插件/脚本/AI 助手，对方凭这个令牌只能调用 `/extension/**` 下的一小撮只读接口，泄露也不影响书签、分享等账号数据。

## 两套鉴权体系，互不相通

| | 令牌管理接口 `/user/access-token/**` | 插件业务接口 `/extension/**` |
|---|---|---|
| 鉴权方式 | Sa-Token `USER` 会话（`satoken` 请求头） | `X-Extension-Token` 请求头 |
| 校验位置 | 全局 Sa-Token 拦截器 | `ExtensionTokenInterceptor`（`bookmarkify-api/.../config/filter/ExtensionTokenInterceptor.kt`） |
| 谁来调用 | Bookmarkify Web 前端本身（登录用户管理自己的令牌） | 第三方插件/脚本/AI 助手（持有一个 AccessToken 明文） |
| uid 获取方式 | `BaseUtils.uid()` | `ExtensionAuthUtils.currentUid()`，读取拦截器写入的 request attribute |

这两套体系刻意不共享 uid 获取逻辑，避免以后有人在 `/extension/**` 里手滑用错鉴权方式。

## 数据模型

`access_token` 表（`AccessTokenEntity`，`bookmarkify-api/.../entity/entity/AccessTokenEntity.kt`）：

| 字段 | 说明 |
|---|---|
| `id` | 令牌 ID |
| `uid` | 所属用户 |
| `name` | 用户自定义备注 |
| `tokenHash` | 令牌明文的 SHA-256，**只存哈希** |
| `tokenPrefix` | 展示用前缀，如 `bmk_ext_a1b2****`，用于列表里辨认 |
| `lastUsedAt` | 最近一次校验通过时间，仅用于展示 |
| `createTime` | 创建时间 |

明文令牌格式为 `bmk_ext_` + 24 字节随机数的十六进制（`AccessTokenServiceImpl.TOKEN_PREFIX`），**只在生成那一刻返回一次**，之后任何地方（含数据库、日志）都读不到明文，只能撤销后重新生成。

## 令牌管理接口（Sa-Token 会话，仅供 Web 前端调用）

```
POST /user/access-token/create   body { "name": "备注" } → 一次性返回明文 token
GET  /user/access-token/list                              → 查看自己名下全部令牌（不含明文）
POST /user/access-token/revoke?id=<id>                     → 撤销令牌
```

对应代码：`AccessTokenController.kt` → `IAccessTokenService` → `AccessTokenServiceImpl.kt`。

## 插件业务接口（`X-Extension-Token`，供第三方调用）

对应代码：`ExtensionController.kt`。人类可读版本见 `bookmarkify-web/pages/access-token/docs.vue`（该页面同时提供机器友好的纯文本，供用户一键复制给 AI 助手）。

### 校验令牌有效性（接入前自检）

```
GET /extension/ping
Header: X-Extension-Token: <token>
```

成功返回该令牌自身信息（`AccessTokenVO`，不含明文）：

```json
{
  "code": 0,
  "msg": "success",
  "data": { "id": "...", "name": "我的脚本", "tokenPrefix": "bmk_ext_a1b2****", "lastUsedAt": "...", "createTime": "..." },
  "ok": true
}
```

第三方应该在**正式开始使用前先调用一次本接口**，确认令牌配置无误（没有多余空格、没有粘贴错），而不是直接拿一个可能有误的令牌去调用有副作用/有限流成本的业务接口。

### 查询网站标题与图标

```
GET /extension/site-info?url=<目标网页URL>
Header: X-Extension-Token: <token>
```

```json
{
  "code": 0,
  "msg": "success",
  "data": { "title": "Example Domain", "favicon": "data:image/png;base64,..." },
  "ok": true
}
```

`favicon` 为 base64 data URL，可能为空。该接口有基础限流（约 300ms 一次）。

### 失败响应（两个接口通用）

令牌缺失、格式不对、不存在或已被撤销，统一返回：

```json
{ "code": 125, "msg": "插件访问令牌无效或已被撤销", "data": null, "ok": false }
```

## 错误码

| 错误码 | 含义 | 触发场景 |
|---|---|---|
| `E124` | 访问令牌不存在或无权操作 | `/user/access-token/revoke` 撤销了不存在或不属于自己的令牌 |
| `E125` | 插件访问令牌无效或已被撤销 | `/extension/**` 缺少/校验失败 `X-Extension-Token` |

## 安全边界

- `X-Extension-Token` 只能访问 `/extension/ping`、`/extension/site-info` 两个只读接口，无法读写书签、分享或账号信息——即便泄露，影响面也被严格限制在"能查任意网页的标题/图标"这一件事上。
- 数据库只存哈希，明文仅在创建那一刻展示一次；丢失只能撤销后重新生成，没有找回入口。
- 调用方不应把 token 放进 URL 查询参数（容易留在日志/浏览器历史里），只应通过 HTTPS 调用，并在请求头中携带。
- 新增 `/extension/**` 下的接口时，权限收紧原则优先：先假设该令牌泄露会造成什么后果，再决定要不要加进这个只读白名单。

## 相关文件

```
bookmarkify-api/.../entity/entity/AccessTokenEntity.kt          # 数据模型
bookmarkify-api/.../server/IAccessTokenService.kt               # 服务接口
bookmarkify-api/.../server/impl/AccessTokenServiceImpl.kt        # 生成/校验/撤销逻辑
bookmarkify-api/.../controller/user/AccessTokenController.kt     # 令牌管理接口(satoken)
bookmarkify-api/.../controller/extension/ExtensionController.kt  # 插件业务接口(X-Extension-Token)
bookmarkify-api/.../config/filter/ExtensionTokenInterceptor.kt   # /extension/** 拦截器
bookmarkify-api/.../utils/ExtensionAuthUtils.kt                  # /extension/** 下取 uid 的工具
bookmarkify-web/components/setting/AccessTokenManage.vue         # 设置页：生成/查看/撤销令牌
bookmarkify-web/pages/access-token/docs.vue                       # 面向第三方/AI 的接口说明页(含一键复制)
bookmarkify-web/typing/accessToken.ts                             # 前端类型定义
```
