<template>
  <!-- h-dvh 而非 h-screen：内层是 overflow-y-auto 的滚动容器，用 100vh 会让容器比可视区高出一条地址栏，底部内容永远滚不出来 -->
  <div class="flex h-dvh w-full flex-col">
    <CommonHeader />
    <div class="flex-1 overflow-y-auto bg-white dark:bg-slate-900">
      <div class="max-w-6xl mx-auto px-4 py-6">
        <button
          type="button"
          class="cy-btn cy-btn-ghost cy-btn-sm mb-4"
          @click="navigateTo('/')">
          <Icon icon="mdi:arrow-left-box" class="size-4" />
          返回
        </button>

        <h1 class="text-xl font-semibold text-slate-900 dark:text-slate-100 mb-4">更多相似书签</h1>

        <template v-if="!node || !node.typeApp">
          <div class="text-sm text-slate-400 dark:text-slate-500 py-10 text-center">未找到该书签</div>
        </template>
        <template v-else>
          <div class="flex items-center gap-3 py-3 px-3 rounded border border-slate-100 dark:border-slate-800 max-w-md mb-8">
            <BookmarkLogo :value="node.typeApp" :size="36" />
            <div class="flex flex-col overflow-hidden flex-1">
              <span class="text-sm font-medium text-slate-700 dark:text-slate-200 truncate">
                {{ node.typeApp.title || node.typeApp.urlBase }}
              </span>
              <span class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ node.typeApp.urlBase }}</span>
            </div>
          </div>

          <!-- 首次加载：骨架屏 -->
          <div v-if="loading" class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <div v-for="i in 6" :key="i" class="flex items-center gap-3 rounded-lg border border-slate-100 dark:border-slate-800 p-3">
              <div class="size-9 rounded-lg bg-slate-200 dark:bg-slate-700 animate-pulse shrink-0" />
              <div class="flex-1 space-y-2">
                <div class="h-3.5 rounded bg-slate-200 dark:bg-slate-700 animate-pulse" :style="{ width: `${45 + ((i * 13) % 40)}%` }" />
                <div class="h-3 rounded bg-slate-100 dark:bg-slate-800 animate-pulse" :style="{ width: `${30 + ((i * 7) % 30)}%` }" />
              </div>
            </div>
          </div>

          <template v-else>
            <div
              v-if="items.length === 0 && !computing"
              class="text-sm text-slate-400 dark:text-slate-500 py-10 text-center">
              暂时没有找到相似的书签
            </div>

            <div v-else class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              <template v-for="item in items" :key="item.domain">
                <!-- 已抓到内容：图标 + 名称 + 域名 + 相似理由 + 添加按钮 -->
                <div
                  v-if="item.bookmark"
                  class="group flex items-start gap-3 rounded-lg border border-slate-100 dark:border-slate-800 p-3 hover:border-slate-200 dark:hover:border-slate-700 transition-colors">
                  <a
                    :href="externalHref(`${item.bookmark.urlScheme}://${item.bookmark.urlHost}`)"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="shrink-0">
                    <img
                      v-if="item.bookmark.logo?.url"
                      :src="item.bookmark.logo.url"
                      class="size-9 rounded-lg object-cover"
                      loading="lazy"
                      alt="" />
                    <div v-else class="size-9 rounded-lg bg-slate-100 dark:bg-slate-800 center">
                      <Icon icon="mdi:earth" class="size-4 text-slate-400" />
                    </div>
                  </a>
                  <div class="flex flex-col overflow-hidden flex-1 min-w-0">
                    <a
                      :href="externalHref(`${item.bookmark.urlScheme}://${item.bookmark.urlHost}`)"
                      target="_blank"
                      rel="noopener noreferrer"
                      class="text-sm font-medium text-slate-700 dark:text-slate-200 truncate hover:text-primary">
                      {{ item.name || item.bookmark.title || item.domain }}
                    </a>
                    <span class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ item.domain }}</span>
                    <span v-if="item.reason" class="mt-1 text-xs text-slate-500 dark:text-slate-400 line-clamp-2">
                      {{ item.reason }}
                    </span>
                  </div>
                  <span
                    v-if="item.alreadyBookmarked"
                    class="shrink-0 text-xs text-slate-400 dark:text-slate-500 whitespace-nowrap">
                    已收藏
                  </span>
                  <button
                    v-else
                    type="button"
                    class="shrink-0 text-xs font-semibold text-primary whitespace-nowrap disabled:opacity-50"
                    :disabled="addingDomain === item.domain"
                    @click="add(item)">
                    {{ addingDomain === item.domain ? '添加中…' : '添加' }}
                  </button>
                </div>

                <!-- AI 推荐、后台还在抓：复用导入占位那套「首字母/圆点 + 脉冲动画 + 原始名称」 -->
                <div
                  v-else
                  class="flex items-center gap-3 rounded-lg border border-dashed border-slate-100 dark:border-slate-800 p-3">
                  <div class="size-9 rounded-lg bg-slate-200 dark:bg-slate-700 animate-pulse shrink-0" />
                  <div class="flex flex-col overflow-hidden flex-1 min-w-0">
                    <span class="text-sm text-slate-400 dark:text-slate-500 truncate">{{ item.name || item.domain }}</span>
                    <span class="text-xs text-slate-300 dark:text-slate-600 truncate">正在获取…</span>
                  </div>
                </div>
              </template>
            </div>

            <!-- 还有 AI 推荐的站点在后台抓取 -->
            <div
              v-if="computing"
              class="mt-4 flex items-center justify-center gap-2 text-xs text-slate-400 dark:text-slate-500">
              <span class="size-3 border-2 border-slate-300 border-t-primary rounded-full animate-spin" />
              正在从网络寻找更多相似书签…
            </div>
          </template>
        </template>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import type { SimilarBookmarkItemVO, UserLayoutNodeVO } from '@typing'
import { bookmarksSimilar, bookmarksLinkOne } from '@api'
import { externalHref } from '@utils'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const bookmarkStore = useBookmarkStore()

// 轮询节奏：AI 推荐要走一遍 DeepSeek + 逐个抓取，几十秒到几分钟不等。
// 与 bookmark.store 里 LOADING 节点的兜底一样按次数递增间隔，覆盖数分钟又不空转。
const POLL_DELAYS_MS = [2500, 2500, 3000, 4000, 5000, 6000, 8000, 10000, 12000, 15000, 20000, 20000]

const loading = ref(true)
const computing = ref(false)
const items = ref<SimilarBookmarkItemVO[]>([])
const addingDomain = ref<string | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollIndex = 0

const node = computed<UserLayoutNodeVO | undefined>(() => {
  const id = route.query.id
  if (typeof id !== 'string') return undefined
  return bookmarkStore.nodes[id]
})

const pageId = computed(() => node.value?.typeApp?.pageId)

async function fetchOnce() {
  const id = pageId.value
  if (!id) {
    loading.value = false
    return
  }
  try {
    const res = await bookmarksSimilar(id)
    items.value = res.items ?? []
    computing.value = !!res.computing
  } catch (error) {
    console.error('[bookmark/similar] 拉取相似书签失败', error)
    computing.value = false
  } finally {
    loading.value = false
  }
  scheduleNextPoll()
}

function scheduleNextPoll() {
  clearPoll()
  if (!computing.value || pollIndex >= POLL_DELAYS_MS.length) return
  pollTimer = setTimeout(() => {
    pollIndex += 1
    fetchOnce()
  }, POLL_DELAYS_MS[pollIndex])
}

function clearPoll() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

async function add(item: SimilarBookmarkItemVO) {
  if (!item.bookmark || addingDomain.value) return
  addingDomain.value = item.domain
  try {
    const created = await bookmarksLinkOne(item.bookmark.id)
    bookmarkStore.addNode(created)
    // 就地标记，避免重复添加；错误提示由 http.ts 统一弹
    items.value = items.value.map((i) => (i.domain === item.domain ? { ...i, alreadyBookmarked: true } : i))
    useToastStore().success('已添加到你的书签')
  } catch (error) {
    console.error('[bookmark/similar] 关联相似书签失败', error)
  } finally {
    addingDomain.value = null
  }
}

onMounted(async () => {
  // 桌面数据可能未加载（直接打开本页 / 刷新）——先确保 node 能查到
  if (!bookmarkStore.isFresh()) {
    await bookmarkStore.update().catch((error) => console.error('[bookmark/similar] 书签刷新失败', error))
  }
  fetchOnce()
})

onBeforeUnmount(clearPoll)
</script>
