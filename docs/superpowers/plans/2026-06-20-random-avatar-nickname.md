# 随机头像 + 随机昵称 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 注册用户自动获得「形容词+名词+数字」随机中文昵称（后端），并在无头像时由前端用 DiceBear `adventurer-neutral` 按 uid 生成确定性头像上传（前端，后端零改动）。

**Architecture:** 昵称在 `bookmarkify-api` 的唯一注册入口 `createVerifiedUser`（经 `UserEntity` 次构造）生成，纯内存词库无外部依赖。头像在 `bookmarkify-web` 登录后置流程 `postLoginSetup` 中，当 `/user/info` 的 `avatarUrl` 为空时用 `@dicebear` 浏览器内生成 SVG，复用既有 `POST /user/uploadAvatar` 上传到 OSS。

**Tech Stack:** Kotlin 2.1 + Spring Boot（API，JUnit5）；Nuxt 4 + Vue 3 + Pinia（Web，pnpm）；`@dicebear/core` + `@dicebear/collection` v9。

## Global Constraints

- 包路径：`top.tcyeee.bookmarkify`。
- API service 命名：`server/` 放接口，`*ServiceImpl` 放实现（本计划不新增 service）。
- 头像风格固定为 DiceBear `adventurer-neutral`（collection 导出名 `adventurerNeutral`），seed 用用户 `uid`。
- 昵称格式：`形容词 + 名词 + 3~4 位数字`，无分隔符；昵称允许重复，不做唯一性校验。
- 头像仅在 `avatarUrl` 为空时生成，绝不覆盖用户已上传头像；生成/上传失败静默降级，不阻塞登录。
- Web 端无单元测试基建，Web 任务以手动验证收尾；勿为此引入 vitest。
- Web 路径别名：`@api` → `server/apis`，`@utils` → `server/utils`，`@stores` → `stores`。

---

## Task 1: 随机昵称生成器（bookmarkify-api）

**Files:**
- Create: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/utils/NicknameGenerator.kt`
- Modify: `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/UserEntity.kt`（次构造 `nickName` 默认值 + 清理未用 import）
- Test: `bookmarkify-api/src/test/kotlin/top/tcyeee/bookmarkify/utils/NicknameGeneratorTest.kt`

**Interfaces:**
- Produces: `object NicknameGenerator` 提供 `fun random(): String` —— 返回如 `"安静的水豚4821"`，即纯中文（`[一-龥]+`）后接 3~4 位数字。

- [ ] **Step 1: Write the failing test**

`bookmarkify-api/src/test/kotlin/top/tcyeee/bookmarkify/utils/NicknameGeneratorTest.kt`:

```kotlin
package top.tcyeee.bookmarkify.utils

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NicknameGeneratorTest {

    @Test
    fun `random returns chinese words followed by 3 to 4 digits`() {
        val pattern = Regex("^[\\u4e00-\\u9fa5]+\\d{3,4}$")
        repeat(300) {
            val name = NicknameGenerator.random()
            assertTrue(name.isNotBlank()) { "昵称不能为空" }
            assertTrue(!name.startsWith("用户_")) { "不应再是旧默认值: $name" }
            assertTrue(pattern.matches(name)) { "格式不符: $name" }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd bookmarkify-api && ./gradlew test --tests "top.tcyeee.bookmarkify.utils.NicknameGeneratorTest"`
Expected: 编译失败 / FAIL —— `NicknameGenerator` 未定义（unresolved reference）。

- [ ] **Step 3: Write minimal implementation**

`bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/utils/NicknameGenerator.kt`:

```kotlin
package top.tcyeee.bookmarkify.utils

import cn.hutool.core.util.RandomUtil

/**
 * 随机中文昵称生成器：「形容词 + 名词 + 3~4 位数字」。
 * 纯内存词库，无外部依赖；昵称允许重复，不做唯一性校验。
 */
object NicknameGenerator {

    private val ADJECTIVES = listOf(
        "安静的", "贪睡的", "快乐的", "机灵的", "慵懒的", "好奇的", "勇敢的", "温柔的",
        "迷糊的", "傲娇的", "呆萌的", "活泼的", "稳重的", "暴躁的", "佛系的", "高冷的",
        "热情的", "腼腆的", "调皮的", "认真的", "随性的", "沉默的", "闪亮的", "神秘的",
        "悠闲的", "倔强的", "天真的", "笨拙的", "敏捷的", "孤独的", "自由的", "幸运的",
        "饥饿的", "困倦的", "微醺的", "清醒的", "复古的", "温暖的", "清凉的", "莽撞的",
    )

    private val NOUNS = listOf(
        "水豚", "青柠", "松鼠", "灯塔", "海獭", "刺猬", "栗子", "云朵", "麋鹿", "柚子",
        "企鹅", "树懒", "海豚", "柠檬", "薄荷", "土拨鼠", "羊驼", "考拉", "浣熊", "锦鲤",
        "月亮", "星尘", "桃子", "汽水", "饼干", "棉花糖", "向日葵", "蒲公英", "贝壳", "灯笼",
        "橡果", "蘑菇", "南瓜", "枫叶", "雪人", "气球", "风筝", "口袋", "罐头", "便签",
    )

    /** 生成随机昵称，如「安静的水豚4821」。 */
    fun random(): String {
        val adj = ADJECTIVES.random()
        val noun = NOUNS.random()
        val suffix = RandomUtil.randomInt(100, 10000) // 100..9999 → 3~4 位
        return "$adj$noun$suffix"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd bookmarkify-api && ./gradlew test --tests "top.tcyeee.bookmarkify.utils.NicknameGeneratorTest"`
Expected: PASS（BUILD SUCCESSFUL）。

- [ ] **Step 5: Wire generator into UserEntity secondary constructor**

修改 `bookmarkify-api/src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/UserEntity.kt`。

将次构造里的：

```kotlin
        nickName = "用户_" + RandomUtil.randomString(5),
```

替换为：

```kotlin
        nickName = NicknameGenerator.random(),
```

在文件顶部 import 区新增：

```kotlin
import top.tcyeee.bookmarkify.utils.NicknameGenerator
```

并删除已不再使用的：

```kotlin
import cn.hutool.core.util.RandomUtil
```

（`IdUtil` 仍在用，保留其 import。）

- [ ] **Step 6: Compile to verify wiring**

Run: `cd bookmarkify-api && ./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL，无「unused import RandomUtil」/未解析引用告警。

- [ ] **Step 7: Commit**

```bash
cd bookmarkify-api
git add src/main/kotlin/top/tcyeee/bookmarkify/utils/NicknameGenerator.kt \
        src/main/kotlin/top/tcyeee/bookmarkify/entity/entity/UserEntity.kt \
        src/test/kotlin/top/tcyeee/bookmarkify/utils/NicknameGeneratorTest.kt
git commit -m "feat(api): generate friendly random nicknames for new users"
```

---

## Task 2: DiceBear 头像生成工具（bookmarkify-web）

**Files:**
- Modify: `bookmarkify-web/package.json`（新增依赖）
- Modify: `bookmarkify-web/server/utils/index.ts`（新增 `generateDefaultAvatarFile`）

**Interfaces:**
- Consumes: `@dicebear/core` 的 `createAvatar`、`@dicebear/collection` 的 `adventurerNeutral`。
- Produces: `export function generateDefaultAvatarFile(seed: string): File` —— 返回 `image/svg+xml` 的 `avatar.svg` 文件，内容为 `adventurer-neutral` 风格、以 `seed` 确定性生成的 SVG。

- [ ] **Step 1: Install DiceBear dependencies**

Run: `cd bookmarkify-web && pnpm add @dicebear/core @dicebear/collection`
Expected: `package.json` 的 `dependencies` 出现 `@dicebear/core` 与 `@dicebear/collection`（v9.x），`pnpm-lock.yaml` 更新，无报错。

- [ ] **Step 2: Add the avatar generator util**

在 `bookmarkify-web/server/utils/index.ts` 顶部 import 区新增：

```ts
import { createAvatar } from '@dicebear/core'
import { adventurerNeutral } from '@dicebear/collection'
```

在文件中追加函数（放在 `randomId()` 之后即可）：

```ts
/**
 * 用 DiceBear adventurer-neutral 按 seed 生成确定性头像 SVG File。
 * 仅客户端调用（依赖 File / Blob）。seed 一般传用户 uid，保证可复现。
 */
export function generateDefaultAvatarFile(seed: string): File {
  const svg = createAvatar(adventurerNeutral, { seed }).toString()
  return new File([svg], 'avatar.svg', { type: 'image/svg+xml' })
}
```

- [ ] **Step 3: Type-check / build to verify the util compiles**

Run: `cd bookmarkify-web && pnpm build`
Expected: 构建成功，无关于 `@dicebear/*` 或 `generateDefaultAvatarFile` 的类型/解析错误。

- [ ] **Step 4: Commit**

```bash
cd bookmarkify-web
git add package.json pnpm-lock.yaml server/utils/index.ts
git commit -m "feat(web): add DiceBear default-avatar generator util"
```

---

## Task 3: 登录后自动补头像（bookmarkify-web）

**Files:**
- Modify: `bookmarkify-web/stores/auth.store.ts`（新增 `ensureDefaultAvatar` action 并在 `postLoginSetup` 中调用）

**Interfaces:**
- Consumes: `generateDefaultAvatarFile(seed)`（Task 2）、`uploadAvatar(file)`（`@api`，签名 `(file: File) => Promise<string>`）、本 store 既有的 `refreshUserInfo()`。`/user/info` 运行时返回对象含 `avatarUrl`（TS 类型 `UserInfo` 未声明，需经 `any` 读取）。
- Produces: `ensureDefaultAvatar(): Promise<void>` action。

- [ ] **Step 1: Import the generator and upload API**

在 `bookmarkify-web/stores/auth.store.ts` 顶部，将现有 api import 行：

```ts
import { authLoginByAccount, authLogout, captchaVerifyEmail, queryUserInfo } from '@api'
```

改为（新增 `uploadAvatar`）：

```ts
import { authLoginByAccount, authLogout, captchaVerifyEmail, queryUserInfo, uploadAvatar } from '@api'
```

并新增：

```ts
import { generateDefaultAvatarFile } from '@utils'
```

- [ ] **Step 2: Add the `ensureDefaultAvatar` action**

在 `actions` 内、`postLoginSetup` 之前（或之后）新增：

```ts
    async ensureDefaultAvatar() {
      // 仅客户端执行（依赖 File / fetch）
      if (!import.meta.client) return
      const account = this.account as (UserInfo & { avatarUrl?: string | null }) | undefined
      if (!account?.uid) return
      // 已有头像则不覆盖，仅在为空时生成
      if (account.avatarUrl) return
      try {
        const file = generateDefaultAvatarFile(account.uid)
        await uploadAvatar(file)
        // 重新拉取以获取带签名的头像 url 并同步到 store
        await this.refreshUserInfo()
      } catch (err) {
        // 失败静默降级，不阻塞登录；下次进入仍会因 avatarUrl 为空而重试
        console.error('[AVATAR] ensureDefaultAvatar failed', err)
      }
    },
```

- [ ] **Step 3: Call it at the end of `postLoginSetup`**

在 `postLoginSetup` 中，现有的并行拉取之后追加调用：

```ts
      await Promise.all([this.refreshUserInfo(), preferenceStore.fetchPreference(), bookmarkStore.update()])
      // 无头像时自动生成并上传默认头像（自愈历史无头像用户）
      await this.ensureDefaultAvatar()
```

- [ ] **Step 4: Build to verify it compiles**

Run: `cd bookmarkify-web && pnpm build`
Expected: 构建成功，无类型错误。

- [ ] **Step 5: Manual verification（需 API 在 :7001 运行）**

Run: `cd bookmarkify-web && pnpm dev`，浏览器走邮箱验证码注册一个新账号。
Expected:
- 注册后昵称形如「形容词+名词+数字」（如「安静的水豚4821」），不再是 `用户_xxxxx`。
- 登录完成后头像自动出现（`adventurer-neutral` 插画风格）；刷新页面头像保持。
- 用同一账号再次进入，不重复生成（`avatarUrl` 已非空）。
- 自愈校验：取一个 `avatarFileId` 为空的历史账号登录，确认自动补上头像。

- [ ] **Step 6: Commit**

```bash
cd bookmarkify-web
git add stores/auth.store.ts
git commit -m "feat(web): auto-generate DiceBear avatar on login when missing"
```

---

## 验证汇总

- `./gradlew test` 中 `NicknameGeneratorTest` 通过；新注册用户昵称符合「形容词+名词+数字」。
- `pnpm build` 通过；新账号登录后自动生成并上传 `adventurer-neutral` 头像，`/user/info` 返回非空 `avatarUrl`，页面展示正常，重复登录不重复生成。
- 已上传自定义头像的用户不被覆盖（`avatarUrl` 非空时跳过生成）。
