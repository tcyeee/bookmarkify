# 回归测试报告

**执行时间：** 2026-08-11 02:25–02:29
**分支：** `prod` @ `ccc236ef`（工作区含未提交改动，见 §5）
**范围：** `bookmarkify-api` / `bookmarkify-web` / `bookmarkify-admin`（`bookmarkify-scrapper` 按要求不在本次范围）
**环境：** macOS (darwin 25.6.0) · JDK 21.0.1 · Node 22.23.0 · pnpm 11.9.0

---

## 1. 结论

**三个服务的生产构建全部通过，API 的 222 个单元测试全绿，可以发布。**

唯一的红灯是 admin 的 `pnpm typecheck`（129 条报错），但它**不是 CI 门禁**，且 129 条全部落在 element-plus 的 CSS-only 导入模式与内嵌的 vben 库代码里，admin 自身业务逻辑一条都没有 —— 判定为既有噪音，不构成本次回归。

| 服务 | 检查项 | 结果 | 关键数字 |
|---|---|---|---|
| api | `./gradlew test` | ✅ PASS | 222 用例 / 0 失败 / 0 跳过 |
| api | `./gradlew bootJar` | ✅ PASS | `bookmarkify-api.jar` 63 MB |
| web | `pnpm typecheck` | ✅ PASS | 0 error |
| web | `pnpm generate` | ✅ PASS | 预渲染 18 条路由 → 11 个 HTML / 16 MB |
| admin | `pnpm typecheck` | ❌ exit 2 | 129 error（全为既有噪音，详见 §3） |
| admin | `pnpm build` | ✅ PASS | 5868 modules / 16.04s / dist 5.2 MB |

---

## 2. bookmarkify-api

### 2.1 单元测试 —— 222 / 222 通过

`./gradlew test`，19 个测试类，无失败无跳过。按领域归类：

| 领域 | 用例数 | 覆盖的关键不变量 |
|---|---:|---|
| 资产 / 图标策略 | 48 | `extractor→role` 映射表、TILE/LIST 两种模式的反向优先级、`resolve` 与 `resolveCover` 不得混用、单字母兜底 |
| 活性巡检 | 43 | `LivenessPolicy.outcomeOf` 三态判定、`blocked` 优先级、404/410 判死而非判活 |
| URL 规范化 | 26 | 追踪参数不产生重复记录、hash 路由参与去重键、末尾斜杠归一 |
| 抓取契约 | 25 | 与 `contract/scrape-response.sample.json` 共享夹具的反序列化、scheme 只升不降 |
| OSS 签名 / 图片处理 | 16 | `signAsset` 分模式尺寸、旧版全 URL 的兼容 |
| 实体 / 认证 / 配置中心 / SSRF | 34 | `ScrapeTargetGuard` 拒裸 IP 与 localhost、WS 握手鉴权、配置写入 diff |

契约夹具的 Kotlin 侧（`ScrapeContractTest`、`SiteAssetIngestorTest`）通过，说明 `ScrapeContract.kt` 与 `contract/scrape-response.sample.json` 是同步的。**Rust 侧的第三个套件本次未跑**（scrapper 不在范围内），若近期改过 `contract.rs` 需要单独验证。

### 2.2 构建

`./gradlew bootJar` 成功，产物 `build/libs/bookmarkify-api.jar` 63 MB。

### 2.3 ⚠️ 发现：CI 不跑测试

`.github/workflows/deploy-api.yml` 只执行 `./gradlew --no-daemon clean bootJar`。Gradle 的 `bootJar` **不依赖 `test`**（已用 `--dry-run` 核实：任务图里没有 test），`build.gradle` 也没有把 `check` 挂上去。

也就是说这 222 个测试目前**没有任何自动化触发点**，全靠人工在本地跑。对照 web —— 它的工作流里 `pnpm typecheck` 是独立一步且排在构建前，失败即阻断部署 —— API 这边缺的是同一件事。建议在 `deploy-api.yml` 的构建步骤前加一步 `./gradlew --no-daemon test`，或直接把命令换成 `clean build`。

---

## 3. bookmarkify-admin

### 3.1 构建 —— 通过

`pnpm build`（先 `build:packages` 构建 `@vben-core/*` 工作区库，再 `vite build --mode production`）exit 0，5868 个模块，16.04s，`dist/` 5.2 MB。

构建过程中 unbuild/mkdist 生成声明文件时打了一条 TS4058（`use-tabs-view-scroll.ts` 引用了无法命名的外部 `Props` 类型），未导致失败，属于库构建阶段的告警。

### 3.2 typecheck —— exit 2，129 条报错（既有噪音）

按错误码拆开：

| 错误码 | 条数 | 性质 |
|---|---:|---|
| TS7016 | 122 | `element-plus/es/components/*/style/css` 这类**纯样式模块没有 `.d.ts`**。CLAUDE.md 已记录为既有噪音 |
| TS2769 | 3 | 内嵌 vben 库：`use-drawer.ts` / `use-modal.ts` / `use-vxe-grid.ts` 的 `h()` 重载不匹配 |
| TS2345 | 2 | 内嵌 vben 库：`preferences.vue` / `use-vxe-grid.ts` 的 props 传参类型 |
| TS2589 | 1 | `use-vxe-grid.vue` 泛型递归过深。CLAUDE.md 已记录为既有噪音 |
| TS2322 | 1 | 内嵌 vben 库：`expandable-arrow.vue` |

**判定为非回归的三条依据：**

1. **admin 工作区是干净的** —— `git status bookmarkify-admin` 无输出，本次改动一个字节都没碰它，所以这些报错必然是 `prod` 分支上的既有状态。
2. **不在 CI 门禁上** —— `deploy-admin.yml` 只有 `pnpm install` → 写 env → `pnpm build` → rsync，没有 typecheck 步骤。CLAUDE.md 也写明这个仓库被 vben 裁剪工具删掉了 lint/test 工具链。
3. **业务代码零报错** —— 那 7 条非 TS7016 的报错**全部**在 `packages/` 下的内嵌 vben 库里，`src/` 下没有一条；`src/` 里的 122 条清一色是同一个 element-plus 样式导入模式，不涉及任何业务类型。

**一处文档缺口：** CLAUDE.md 把噪音描述为"element-plus 的 CSS-only 样式导入 + 一个 vxe-table 泛型深度告警"，这只解释了 123 条；剩下 6 条（TS2769×3 / TS2345×2 / TS2322×1）没有记录在案。它们同样在 `packages/` 里、同样与业务无关，但下次有人跑 typecheck 时会以为是新问题。建议把这句改成"element-plus 样式导入 + `packages/` 下 vben 库的若干类型报错"，并记下当前基线条数 **129**，让后续能靠数字增减判断是不是真有新问题。

---

## 4. bookmarkify-web

### 4.1 typecheck —— 0 error

与 CLAUDE.md 记录的"clean (0 errors)"一致。

**过程中出现过一次失败，已确认是编辑中途的快照，非回归：** 首次运行时 `plugins/version.client.ts` 报两条错（`useEventListener` 未定义、一处多余的类型参数）。该文件的写入时间是 02:25:54，正落在首次运行与复查之间 —— 当时读到的是一个尚未保存完的中间版本。当前磁盘上的版本用 `document.addEventListener` 且无类型参数，复查 **exit 0 / 0 error**。

顺带印证了 web 的 CI 门禁是有效的：这种错误 `nuxt generate` 完全不会发现（vite 只剥类型不检查），只有独立的 typecheck 步骤能拦住。

### 4.2 生产构建 —— 通过

按 CI 的方式执行 `NUXT_BACKEND=https://bookmarkify.cc pnpm generate`：预渲染 18 条路由，2.1s，产物 `.output/public` 16 MB，含 11 个 HTML。SPA 兜底用的 `200.html`、SEO 用的 `welcome/index.html` 均已生成。

### 4.3 针对本次改动的专项验证：发版自动升级链路

未提交的改动引入了"发版后已开着的标签页自动升级"整条链路。构建层面能验证的三个环节全部到位：

| 环节 | 验证方式 | 结果 |
|---|---|---|
| 版本探针产出 | 检查 `_nuxt/builds/latest.json` | ✅ `{"id":"61ea2099-…","timestamp":1786386439625}` |
| 客户端有对照基准 | 在预渲染 HTML 里查 `config.app.buildId` | ✅ `app:{baseURL:"/",buildId:"61ea2099-…"}`，与 latest.json 的 id 一致 |
| 探测间隔生效 | `nuxt.config.ts` 的 `experimental.checkOutdatedBuildInterval` | ✅ 5 min（默认 1h） |

第二项是这条链路里最容易静默失效的一环：`version.client.ts` 判的是 `meta.id !== config.app.buildId`，一旦 `buildId` 在产物里拿不到（undefined），比较恒为真 —— 页面不可见时会**静默重载**，可见时会**每次切回标签页都弹一次升级提示**。已确认 buildId 被写进了每个预渲染 HTML 的 runtime config，不存在这个风险。

---

## 5. 本次未提交改动清单

```
 M .github/workflows/deploy-web.yml      +33/-3   两趟 rsync + 14 天旧 chunk 清扫
 M bookmarkify-web/nuxt.config.ts        +7       注册 version 插件 + 探测间隔 5min
 M deploy/nginx/bookmakify.cc.conf       +32      静态站点缓存分层
?? bookmarkify-web/plugins/version.client.ts      新版本探测与升级处理
```

改动只涉及 web 与部署配置，api / admin / scrapper 未受影响 —— 这也是 §3 判定 admin 报错为既有问题的前提。

---

## 6. 本次未覆盖的部分

以下几项本地无法执行，报告不对它们做任何结论：

- **nginx 配置语法** —— 本机没有 nginx 与 docker，`deploy/nginx/bookmakify.cc.conf` 的 32 行改动只做了人工审阅，未跑 `nginx -t`。上线后需用 `curl -I https://bookmarkify.cc/` 核对：每个响应应当**只有一个** `Cache-Control`（出现两个说明有 `expires` 指令在和 `add_header` 打架），且 `/` 为 `no-cache`、`/_nuxt/` 为 `immutable`、`/_nuxt/builds/latest.json` 为 `no-store`。
- **部署工作流的 rsync 逻辑** —— 两趟 rsync 的顺序、`--exclude='/_nuxt/'` 的锚定、14 天清扫的 mtime 行为，都只能在真实部署时验证。
- **运行时 / 端到端** —— 没有起 PostgreSQL、Redis、WebSocket，也没有跑浏览器。加书签全流程、巡检、OSS 签名等只有单元测试层面的保障。
- **bookmarkify-scrapper** —— 按要求排除。注意共享契约夹具 `contract/scrape-response.sample.json` 由三个套件消费，本次只跑了 Kotlin 的两个。
- **admin 的运行时行为** —— 该仓库没有测试框架，只有 typecheck 与构建两道保障。

---

## 7. 建议的后续动作

按性价比排序：

1. **给 `deploy-api.yml` 加测试门禁**（§2.3）—— 222 个测试现在完全靠自觉，成本只是加一步 `./gradlew --no-daemon test`。
2. **更新 admin CLAUDE.md 的噪音描述并记下基线 129 条**（§3.2）—— 让"红灯是否变多"变成一个可判断的问题。
3. 上线后按 §6 第一条人工核对一次缓存头，这是发版自动升级链路唯一没被构建覆盖的地基。
