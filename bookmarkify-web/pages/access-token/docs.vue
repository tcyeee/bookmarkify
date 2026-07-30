<template>
  <div class="flex h-screen w-full flex-col">
    <CommonHeader />
    <div class="flex-1 overflow-y-auto bg-white dark:bg-slate-900">
      <div class="max-w-3xl mx-auto px-4 py-6 text-slate-900 dark:text-slate-100">
        <div class="flex items-start justify-between gap-4">
          <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" @click="navigateTo('/setting')">
            <Icon icon="mdi:arrow-left-box" class="size-4" />
            {{ $t('back') }}
          </button>
          <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm shrink-0" @click="copyDocs">
            <Icon icon="mdi:content-copy" class="size-4" />
            {{ $t('accessTokenManage.docsPage.copy') }}
          </button>
        </div>

        <h1 class="mt-4 text-xl font-semibold">{{ $t('accessTokenManage.docsPage.title') }}</h1>
        <p class="mt-1 text-sm text-slate-500 dark:text-slate-400">{{ $t('accessTokenManage.docsPage.desc') }}</p>

        <section class="mt-8">
          <h2 class="text-base font-semibold">这是什么</h2>
          <p class="mt-2 text-sm leading-relaxed text-slate-600 dark:text-slate-300">
            AccessToken 是一种与你的账号登录会话（satoken）完全隔离的只读凭证，专供浏览器插件 / 自动化脚本 / AI 助手调用，
            用于查询任意网页的标题与图标。泄露该 token 不会影响你的书签、分享等账号数据，只能用于这一个只读接口。
          </p>
        </section>

        <section class="mt-8">
          <h2 class="text-base font-semibold">鉴权方式</h2>
          <p class="mt-2 text-sm leading-relaxed text-slate-600 dark:text-slate-300">每次请求在 HTTP 请求头中携带：</p>
          <pre class="mt-2 rounded-lg bg-slate-100 dark:bg-slate-800 px-4 py-3 text-xs font-mono overflow-x-auto"><code>X-Extension-Token: &lt;你的 AccessToken 明文&gt;</code></pre>
          <div class="cy-alert cy-alert-warning mt-3 items-start text-sm">
            <Icon icon="mdi:alert-outline" class="size-5 shrink-0" />
            <ul class="list-disc pl-4 space-y-1">
              <li>token 只在生成时展示一次，请妥善保存；一旦丢失只能撤销后重新生成。</li>
              <li>不要把 token 放进 URL 查询参数，避免出现在日志或浏览器历史中。</li>
              <li>只应通过 HTTPS 调用。</li>
            </ul>
          </div>
        </section>

        <section class="mt-8">
          <h2 class="text-base font-semibold">接口</h2>

          <h3 class="mt-4 text-sm font-semibold text-slate-700 dark:text-slate-200">校验令牌有效性(接入前自检)</h3>
          <pre
            class="mt-2 rounded-lg bg-slate-100 dark:bg-slate-800 px-4 py-3 text-xs font-mono overflow-x-auto"
          ><code>GET {{ apiBase }}/extension/ping

Header:
  X-Extension-Token: &lt;token&gt;</code></pre>

          <p class="mt-3 text-sm text-slate-600 dark:text-slate-300">curl 示例：</p>
          <pre
            class="mt-2 rounded-lg bg-slate-100 dark:bg-slate-800 px-4 py-3 text-xs font-mono overflow-x-auto"
          ><code>curl -H "X-Extension-Token: YOUR_TOKEN" "{{ apiBase }}/extension/ping"</code></pre>

          <p class="mt-3 text-sm text-slate-600 dark:text-slate-300">成功响应（返回该令牌自身信息，不含明文）：</p>
          <pre
            class="mt-2 rounded-lg bg-slate-100 dark:bg-slate-800 px-4 py-3 text-xs font-mono overflow-x-auto"
          ><code>{
  "code": 0,
  "msg": "success",
  "data": { "id": "...", "name": "我的脚本", "tokenPrefix": "bmk_ext_a1b2****", "lastUsedAt": "...", "createTime": "..." },
  "ok": true
}</code></pre>
          <p class="mt-1 text-xs text-slate-400 dark:text-slate-500">
            失败响应与下方 site-info 一致(code 125)。建议第三方在首次接入时先调用本接口自检，确认无误后再调用真正的业务接口。
          </p>

          <h3 class="mt-6 text-sm font-semibold text-slate-700 dark:text-slate-200">查询网站标题与图标</h3>
          <pre
            class="mt-2 rounded-lg bg-slate-100 dark:bg-slate-800 px-4 py-3 text-xs font-mono overflow-x-auto"
          ><code>GET {{ apiBase }}/extension/site-info?url=&lt;目标网页URL&gt;

Header:
  X-Extension-Token: &lt;token&gt;</code></pre>

          <p class="mt-3 text-sm text-slate-600 dark:text-slate-300">curl 示例：</p>
          <pre
            class="mt-2 rounded-lg bg-slate-100 dark:bg-slate-800 px-4 py-3 text-xs font-mono overflow-x-auto"
          ><code>curl -H "X-Extension-Token: YOUR_TOKEN" "{{ apiBase }}/extension/site-info?url=https://example.com"</code></pre>

          <p class="mt-3 text-sm text-slate-600 dark:text-slate-300">成功响应：</p>
          <pre
            class="mt-2 rounded-lg bg-slate-100 dark:bg-slate-800 px-4 py-3 text-xs font-mono overflow-x-auto"
          ><code>{
  "code": 0,
  "msg": "success",
  "data": { "title": "Example Domain", "favicon": "data:image/png;base64,..." },
  "ok": true
}</code></pre>
          <p class="mt-1 text-xs text-slate-400 dark:text-slate-500">favicon 为 base64 data URL，可能为空。</p>

          <p class="mt-3 text-sm text-slate-600 dark:text-slate-300">失败响应（token 无效或已被撤销）：</p>
          <pre
            class="mt-2 rounded-lg bg-slate-100 dark:bg-slate-800 px-4 py-3 text-xs font-mono overflow-x-auto"
          ><code>{
  "code": 125,
  "msg": "插件访问令牌无效或已被撤销",
  "data": null,
  "ok": false
}</code></pre>

          <p class="mt-3 text-sm text-slate-600 dark:text-slate-300">
            限流：该接口有基础限流（约 300ms 一次），请勿高频轮询同一 token。
          </p>
        </section>

        <section class="mt-8">
          <h2 class="text-base font-semibold">令牌管理接口</h2>
          <p class="mt-2 text-sm text-slate-600 dark:text-slate-300">走正常登录会话，非本 token 鉴权，仅供参考不建议 AI 直接调用。</p>
          <pre
            class="mt-2 rounded-lg bg-slate-100 dark:bg-slate-800 px-4 py-3 text-xs font-mono overflow-x-auto"
          ><code>POST /user/access-token/create   body { "name": "备注" } → 一次性返回明文 token
GET  /user/access-token/list                              → 查看自己名下全部令牌（不含明文）
POST /user/access-token/revoke?id=&lt;id&gt;                     → 撤销令牌</code></pre>
        </section>

        <section class="mt-8 mb-4">
          <h2 class="text-base font-semibold">安全边界</h2>
          <ul class="mt-2 list-disc pl-5 space-y-1 text-sm text-slate-600 dark:text-slate-300">
            <li>该 token 仅能访问 /extension/ping、/extension/site-info 两个只读接口，无法读写书签、分享或账号信息。</li>
            <li>请勿将 token 硬编码进公开代码仓库或分享给他人。</li>
          </ul>
        </section>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
definePageMeta({ middleware: 'auth' })

const { t: translate } = useI18n()
const toastStore = useToastStore()
const runtimeConfig = useRuntimeConfig()

const apiBase = runtimeConfig.public.apiBase

// 供 AI / 自动化工具直接阅读的接口说明纯文本，保持机器友好的结构（无需 markdown 渲染），方便用户一键复制后粘贴给 AI 助手
const docsContent = computed(() => {
  return `# AccessToken 接口使用说明

## 这是什么
AccessToken 是一种与你的账号登录会话（satoken）完全隔离的只读凭证，专供浏览器插件 / 自动化脚本 / AI 助手调用，
用于查询任意网页的标题与图标。泄露该 token 不会影响你的书签、分享等账号数据，只能用于这一个只读接口。

## 鉴权方式
每次请求在 HTTP 请求头中携带：

  X-Extension-Token: <你的 AccessToken 明文>

注意：
- token 只在生成时展示一次，请妥善保存；一旦丢失只能撤销后重新生成。
- 不要把 token 放进 URL 查询参数，避免出现在日志或浏览器历史中。
- 只应通过 HTTPS 调用。

## 接口

### 校验令牌有效性(接入前自检)
GET ${apiBase}/extension/ping

Header:
  X-Extension-Token: <token>

curl 示例：
  curl -H "X-Extension-Token: YOUR_TOKEN" "${apiBase}/extension/ping"

成功响应（返回该令牌自身信息，不含明文）：
  {
    "code": 0,
    "msg": "success",
    "data": { "id": "...", "name": "我的脚本", "tokenPrefix": "bmk_ext_a1b2****", "lastUsedAt": "...", "createTime": "..." },
    "ok": true
  }

失败响应与 site-info 一致(code 125)。建议第三方在首次接入时先调用本接口自检，确认无误后再调用真正的业务接口。

### 查询网站标题与图标
GET ${apiBase}/extension/site-info?url=<目标网页URL>

Header:
  X-Extension-Token: <token>

curl 示例：
  curl -H "X-Extension-Token: YOUR_TOKEN" "${apiBase}/extension/site-info?url=https://example.com"

成功响应：
  {
    "code": 0,
    "msg": "success",
    "data": { "title": "Example Domain", "favicon": "data:image/png;base64,..." },
    "ok": true
  }
  favicon 为 base64 data URL，可能为空。

失败响应（token 无效或已被撤销）：
  {
    "code": 125,
    "msg": "插件访问令牌无效或已被撤销",
    "data": null,
    "ok": false
  }

限流：该接口有基础限流（约 300ms 一次），请勿高频轮询同一 token。

## 令牌管理接口（走正常登录会话，非本 token 鉴权，仅供参考不建议 AI 直接调用）
POST /user/access-token/create   body { "name": "备注" } → 一次性返回明文 token
GET  /user/access-token/list                              → 查看自己名下全部令牌（不含明文）
POST /user/access-token/revoke?id=<id>                     → 撤销令牌

## 安全边界
- 该 token 仅能访问 /extension/ping、/extension/site-info 两个只读接口，无法读写书签、分享或账号信息。
- 请勿将 token 硬编码进公开代码仓库或分享给他人。`
})

async function copyDocs() {
  try {
    await navigator.clipboard.writeText(docsContent.value)
    toastStore.success(translate('accessTokenManage.revealDialog.copySuccess'))
  } catch (error) {
    console.error('[access-token/docs] 复制文档失败', error)
  }
}
</script>
