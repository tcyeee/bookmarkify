# 随机头像 + 随机昵称 设计

- 日期：2026-06-20
- 涉及服务：`bookmarkify-api`（昵称）、`bookmarkify-web`（头像）
- 背景：匿名/游客登录已删除，所有用户均经邮箱验证码注册产生（`UserServiceImpl.createVerifiedUser`）。当前新用户昵称为 `"用户_" + 5 位随机字符`（机器感强），头像为空（`avatarFileId` 为 null，前端展示占位）。

## 目标

1. 注册时生成更友好的随机中文昵称（「形容词+名词+数字后缀」）。
2. 用户首次进入时若无头像，前端用 DiceBear（`adventurer-neutral` 风格）按 uid 生成确定性头像并上传，复用现有 OSS 头像体系。

非目标：不引入唯一性约束；不为昵称/头像做后台批量回填脚本（前端登录时按需自愈）。

---

## Part 1 — 随机昵称（bookmarkify-api）

### 改动点
- 唯一注册入口 `UserServiceImpl.createVerifiedUser` → 经 `UserEntity(deviceId)` 次构造设置 `nickName`。
- 将次构造里的 `nickName = "用户_" + RandomUtil.randomString(5)` 替换为 `NicknameGenerator.random()`。

### 新增 `NicknameGenerator`
- 位置：`top.tcyeee.bookmarkify.utils.NicknameGenerator`（`object`）。
- 两份内联中文词库常量（零外部依赖）：
  - `ADJECTIVES`：约 40 个形容词（如「安静的」「贪睡的」「快乐的」「机灵的」……，统一带「的」结尾）。
  - `NOUNS`：约 40 个名词（如「水豚」「青柠」「松鼠」「灯塔」……）。
- 生成规则：`随机形容词 + 随机名词 + 随机数字后缀`。
  - 后缀：`RandomUtil.randomInt(100, 10000)`（3–4 位），直接拼接，无分隔符。
  - 示例：`安静的水豚4821`、`贪睡的青柠237`（注：237 为 3 位时即 `100..999`）。
- 组合空间：40 × 40 × ~9900 ≈ 1.5e7，重复观感可忽略；昵称允许重复，不做去重校验。

### 接口/契约
- 无新增/变更接口。注册返回的 `UserSessionInfo.nickName` 即为新昵称；`/user/me` 与既有读取路径不变。

### 边界
- 词库与拼接逻辑纯内存，无 I/O、无异常路径。

---

## Part 2 — 随机头像（bookmarkify-web 为主，API 零改动）

### 依赖
- web 端新增 `@dicebear/core` 与 `@dicebear/collection`（提供 `adventurerNeutral` 风格）。

### 触发时机
- 登录/注册成功后，从 `GET /user/me`（`UserInfoShow.avatarUrl`）读取头像。
- 当 `avatarUrl` 为 `null/空` 时触发生成与上传 → 同时为历史无头像用户自愈（生成一次后持久化，不再重复触发）。

### 生成与上传流程
1. `createAvatar(adventurerNeutral, { seed: uid }).toString()` → 得 SVG 字符串（seed 用 uid，确定性、稳定、可复现）。
2. `new Blob([svg], { type: 'image/svg+xml' })` → 包成 `File`（如 `avatar.svg`）。
3. `POST /user/uploadAvatar`（multipart，字段名 `file`，与现有上传一致）。
4. 用接口返回的头像 url 更新本地用户状态（store / `me` 缓存）。

### 后端
- 零改动：复用 `UserController.uploadAvatar` → `UserServiceImpl.updateAvatar` → `FileServiceImpl.updateAvatar` → `OssUtils.uploadUserFile(AVATAR)` → 写 `user_file` 并回填 `avatarFileId`。
- SVG 作为普通文件存入 OSS，`<img>` 可直接展示；`avatarUrlWithSign()` 逻辑不变。

### 边界与失败处理
- 生成/上传失败：静默降级为「无头像占位」，不阻塞登录主流程；可在下次进入时再次尝试（因 `avatarUrl` 仍为 null）。
- 仅在 `avatarUrl` 为空时执行，避免覆盖用户已上传的自定义头像。

---

## 验证

- 昵称：单元/手动验证 `NicknameGenerator.random()` 多次输出符合「形容词+名词+数字」格式且非空；新注册用户 `nickName` 不再是 `用户_xxxxx`。
- 头像：注册新账号 → 确认前端自动生成 `adventurer-neutral` SVG 并成功上传，`/user/me` 返回非空 `avatarUrl`，页面正常展示；重复进入不再重复生成。
- 自愈：取一个 `avatarFileId` 为空的历史用户登录，确认自动补头像。
