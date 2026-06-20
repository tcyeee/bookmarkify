# 移除访客登录 + 移除短信登录 — 设计文档

日期：2026-06-20
范围：`bookmarkify-api`（Kotlin/Spring Boot）+ `bookmarkify-web`（Nuxt/Vue）

## 背景

当前"访客登录"= `GET /auth/track`（`@SaIgnore`）：无登录态时生成 `deviceUid` cookie → 按 deviceId 查/建匿名 `sys_user` → 初始化默认书签/偏好 → Sa-Token 登录 → 返回 token。这是"浏览器指纹登录"。

关键耦合：邮箱/短信验证码登录**架构在 track 之上**——`sendEmail`/`verifyEmail`/`verifySms` 都用 `BaseUtils.uid()`（无会话抛 E101），验证码以 `<uid>` 为 key 存储；`verifyCodeAndBind` 的"无既有用户"分支是把邮箱/手机**绑定到当前匿名用户**。

目标：

1. 彻底移除访客登录——删除 `/auth/track` 与匿名用户自动创建；验证码登录改为自包含（按邮箱存验证码，验证通过即创建/登录正式用户）。
2. 整条移除短信登录链路（含图形验证码、设置页绑定手机）。
3. 密码登录（`/auth/login`，前端已隐藏）保留不动。

## 决策

- 新邮箱首次验证通过 → 直接创建 `verified=true` 正式用户并登录（"验证即注册"）。
- token 失效/无 token → 前端直接 `logout()` 跳 `/welcome`，不再静默 track 重登。
- `UserEntity.phone` / `UserSessionInfo.phone` 数据库列保留（删列需迁移，超出本次范围），重构后恒为 null。
- 存量无 email/phone 的匿名 `sys_user` 自然失去登录入口，不做迁移/清理（可日后处理）。

## 后端改动（bookmarkify-api）

### A. 移除访客登录

- 删除：`/auth/track` 端点（`LoginController`）、`UserServiceImpl.track`、`queryOrRegisterByDeviceId`、`BaseUtils.sessionRegisterDeviceId`、`IUserService.track`。
- 新增 `UserServiceImpl.createVerifiedUser(email: String? = null)`：构造 `UserEntity`（随机 deviceId 仅满足非空列）、`email`、`verified=true` → `save` → 初始化默认数据（搬自原 track）：
  - `bookmarkService.setDefaultFunction(id)`
  - `userPreferenceMapper.insert(UserPreferenceEntity(uid = id))`
  - `bookmarkService.setDefaultBookmark(id)`
- `verifyCodeAndBind` 重构为 `verifyCodeAndLogin`：校验验证码 → `findUser() ?: createVerifiedUser(email)` → `StpKit.USER.logout()` + `session.clear()` + `login(id, true)` → `authVO(token).writeToSession()`。删除"绑定到当前用户"分支及其 `uid`/`bindToCurrentUser`/`updateSession` 参数。
- 邮箱验证码 Redis key：`CODE_EMAIL:<uid>` → `CODE_EMAIL:<email>`（`sendEmail` 存、`verifyEmail` 取均按 email）。
- `LoginController`：`captcha/email`、`captcha/verifyEmail` 加 `@SaIgnore`，限流改 `@Throttle(byIp = true)`，签名去掉 `BaseUtils.uid()`。

### B. 移除短信（整条链路）

- `LoginController` 删除端点：`captcha/image`、`captcha/sms`、`captcha/verifySms`。
- `UserServiceImpl` 删除 `sendSms`、`verifySms`、`captchaImage`，并移除构造函数中 `smsService: SmsServiceImpl` 依赖。
- 删除短信代码单元：`SmsServiceImpl`、`ISmsService`、`utils/SmsUtils`、`entity/entity/SmsRecord`、`mapper/SmsRecordMapper`、`config/entity/sms/*`（`SmsStatus`/`SmsType`/`SmsResponse`/`SmsParams`），以及相关 Aliyun SMS 配置项与环境变量声明。
- `RedisType` 删除 `CODE_PHONE`、`CAPTCHA_CODE`。
- `ErrorType` 删除仅短信用到的码（`E214` 短信发送失败、`E302` 图形验证码不匹配）——实现时 grep 确认无其他引用再删。
- 保留：`UserEntity.phone` / `UserSessionInfo.phone` 列；`loginByAccount` 仍兼容 email/phone 匹配（密码登录端点保留，phone 恒空，无害）。
- `DelayTaskScheduler` 保留（仅移除其短信回执用途）。

## 前端改动（bookmarkify-web）

### A. 移除访客登录

- `stores/auth.store.ts`：删除 `loginOrRegister()`、`track` import、模块级 `loginInFlight`。`refreshUserInfo` 的 `202` 分支改为仅 `await this.logout()`（跳 `/welcome`）后 reject，不再静默重登。
- `server/apis/http.ts`：移除 3 处 `loginOrRegister()` bootstrap（`uploadFile`、`start`、`handleResult` 的 `101` 分支）。无 token 的受保护请求 / `101` token 过期 → `authStore.logout()` 跳 `/welcome` 并 reject。
- `components/welcome/login/EmailLoginPanel.vue`：删除 `sendCode` 中发码前的 `if (!authStore.account?.token) await authStore.loginOrRegister()`；直接 `captchaSendEmail`（现为公开端点）。
- `server/apis/index.ts`：删除 `export const track`。
- `middleware/auth.ts`（已要求 `AUTHED`）保持不变。

### B. 移除短信

- 删除组件：`components/welcome/login/PhoneLoginPanel.vue`、`components/setting/account/BindPhoneModal.vue`。
- `components/welcome/login/WelcomeLoginDialog.vue`：移除 phone tab 与 `PhoneLoginPanel` 的 import/render 残留。
- `components/setting/account/AccountProfile.vue`：移除绑定手机区块（`maskedPhone`、`form.phone`、`handlePhoneBindSuccess`、`BindPhoneModal` 引用）。
- `components/setting/account/verify.vue`：规划/实现时确认是否死代码——是则整删，否则删手机输入部分。
- `stores/auth.store.ts`：删除 `loginWithPhone`、`captchaVerifySms` import。
- `server/apis/index.ts`：删除 `captchaImage`、`captchaSendSms`、`captchaVerifySms`。
- `stores/sys.store.ts`：删除 `smsCountdown`、`smsCountdownTimer`、`startSmsCountdown`、`stopSmsCountdown`。
- `typing/user.ts`：删除 `CaptchaSmsParams`、`SmsVerifyParams`；登录方式 key union 去掉 `'phone'` 与 `'guest'`。
- `typing/setting.ts`：清理 `phone?` 相关类型（随 AccountProfile 调整）。

## 影响 / 风险

- 邮箱发码端点公开后存在被刷邮件风险，靠 `@Throttle(byIp = true)` 限流（与现有 `/auth/login`、原 `/auth/track` 的 `@SaIgnore` 暴露面一致）。
- 前后端需同步发布：前端不再调用 `track`/短信端点，后端删除这些端点；中途单边发布会导致登录不可用。
- 验证码 key 从 `<uid>` 改为 `<email>`，发布瞬间在途旧验证码会失效（可接受）。

## 验证

- 后端：`./gradlew bootJar` 编译通过（无对已删类型的悬挂引用）；手测邮箱新用户"验证即注册"、老用户验证登录、token 过期重登跳转。
- 前端：`pnpm build` 通过；手测邮箱登录全流程、未登录访问 `/` 跳 `/welcome`、设置页无手机相关入口。
- 无自动化测试套件（两端均无 test runner），以构建 + 手测为准。
