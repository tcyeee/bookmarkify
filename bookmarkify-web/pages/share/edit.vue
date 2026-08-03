<template>
  <div class="flex h-screen w-full flex-col">
    <CommonHeader />
    <div class="flex-1 overflow-y-auto bg-white dark:bg-slate-900">
      <div class="max-w-2xl mx-auto px-4 py-6">
        <div class="flex items-center gap-2 mb-4">
          <button type="button" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300" @click="navigateTo('/')">
            <Icon icon="mdi:arrow-left" class="size-5" />
          </button>
          <h1 class="text-xl font-semibold text-slate-900 dark:text-slate-100">分享书签</h1>
        </div>

        <template v-if="!published">
          <!-- 书签选择 -->
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 overflow-hidden mb-6">
            <div class="flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/60 border-b border-slate-200 dark:border-slate-700">
              <span class="text-sm text-slate-600 dark:text-slate-300">已选择 <span class="font-semibold text-primary">{{ checkedIds.size }}</span> 个书签</span>
              <input
                v-model="filterQuery"
                type="text"
                class="cy-input cy-input-sm w-40"
                placeholder="筛选书签..." />
            </div>
            <div class="max-h-80 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
              <div v-if="filteredBookmarks.length === 0" class="text-sm text-slate-400 dark:text-slate-500 py-6 text-center">暂无书签</div>
              <label
                v-for="node in filteredBookmarks"
                :key="node.id"
                :class="
                  cn(
                    'flex items-center gap-3 px-4 py-2.5 transition-colors',
                    shareable(node)
                      ? 'hover:bg-slate-50 dark:hover:bg-slate-800/50 cursor-pointer'
                      : 'cursor-not-allowed opacity-60',
                  )
                ">
                <input
                  type="checkbox"
                  class="cy-checkbox cy-checkbox-sm"
                  :checked="checkedIds.has(node.id)"
                  :disabled="!shareable(node)"
                  @change="toggleNode(node.id)" />
                <BookmarkLogo :value="node.typeApp!" :size="20" />
                <div class="flex-1 min-w-0">
                  <p
                    :class="
                      cn(
                        'text-sm font-medium truncate',
                        shareable(node) ? 'text-slate-700 dark:text-slate-200' : 'text-slate-400 dark:text-slate-500',
                      )
                    ">
                    {{ node.typeApp!.title || node.typeApp!.urlBase }}
                  </p>
                  <p class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ node.typeApp!.urlBase }}</p>
                </div>
              </label>
            </div>
          </div>

          <!-- 分享文案 -->
          <label class="block mb-6">
            <span class="text-sm text-slate-600 dark:text-slate-300">分享文案</span>
            <textarea
              v-model="note"
              rows="3"
              maxlength="500"
              class="cy-textarea w-full mt-1"
              placeholder="写点什么介绍一下这些书签吧（可选）" />
          </label>

          <!-- 有效期 -->
          <div class="mb-6">
            <label class="flex items-center gap-2 cursor-pointer">
              <input type="checkbox" class="cy-checkbox cy-checkbox-sm" v-model="hasExpiry" />
              <span class="text-sm text-slate-600 dark:text-slate-300">设置链接有效期（默认永不失效）</span>
            </label>
            <input
              v-if="hasExpiry"
              v-model="expireDate"
              type="date"
              :min="minDate"
              class="cy-input cy-input-sm mt-2" />
          </div>

          <button
            type="button"
            class="cy-btn cy-btn-primary w-full"
            :disabled="checkedIds.size === 0 || publishing"
            @click="publish">
            {{ publishing ? '发布中...' : '发布分享' }}
          </button>
        </template>

        <!-- 未通过审核 -->
        <template v-else-if="rejectReason">
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 p-6 text-center">
            <Icon icon="mdi:alert-circle" class="size-10 text-rose-500 mx-auto mb-3" />
            <p class="text-sm text-slate-600 dark:text-slate-300 mb-1">分享未通过审核，未发布</p>
            <p class="text-sm text-rose-500 mb-4">驳回理由：{{ rejectReason }}</p>
            <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" @click="navigateTo('/')">返回首页</button>
          </div>
        </template>

        <!-- 发布成功 -->
        <template v-else>
          <div class="rounded-xl border border-slate-200 dark:border-slate-700 p-6 text-center">
            <Icon icon="mdi:check-circle" class="size-10 text-emerald-500 mx-auto mb-3" />
            <p class="text-sm text-slate-600 dark:text-slate-300 mb-4">分享已发布，任何人都可以通过下面的链接查看</p>
            <div class="flex items-center gap-2 mb-4">
              <input readonly class="cy-input flex-1 text-sm" :value="shareUrl" @focus="($event.target as HTMLInputElement).select()" />
              <button type="button" class="cy-btn cy-btn-primary cy-btn-sm shrink-0" @click="copyLink">复制链接</button>
            </div>
            <button type="button" class="cy-btn cy-btn-ghost cy-btn-sm" @click="navigateTo('/')">返回首页</button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { BookmarkLinkType, HomeItemType, ShareStatus, type UserLayoutNodeVO } from '@typing'
import { shareCreate } from '@api'
import { cn } from '@utils'
import BookmarkLogo from '@/components/launchpad/cell/BookmarkLogo.vue'

definePageMeta({ middleware: 'auth' })

// 失效(不可访问)以及本地/IP 类型的书签对分享的接收方没有意义：不可选中，标题置灰
function shareable(node: UserLayoutNodeVO) {
  const app = node.typeApp
  if (!app) return false
  if (app.isActivity === false) return false
  return app.linkType !== BookmarkLinkType.LOCAL && app.linkType !== BookmarkLinkType.IP
}

const route = useRoute()
const bookmarkStore = useBookmarkStore()
const runtimeConfig = useRuntimeConfig()
const { $track } = useNuxtApp()

const folderId = (route.query.folderId as string) ?? ''

// 与该文件夹相关的初始书签集合：能在 store 中按 id 命中一个真实的文件夹节点，就用其子项；
// 否则（分享按钮来自"根目录"卡片）退化为取顶层非文件夹节点
const initialNodeIds = (() => {
  const folderNode = bookmarkStore.nodes[folderId]
  const source =
    folderNode?.type === HomeItemType.BOOKMARK_DIR
      ? bookmarkStore.childrenOf(folderId)
      : bookmarkStore.rootNodes.filter((n) => n.type !== HomeItemType.BOOKMARK_DIR)
  return source.filter((n) => n.type === HomeItemType.BOOKMARK && shareable(n)).map((n) => n.id)
})()

const checkedIds = ref(new Set<string>(initialNodeIds))

function toggleNode(id: string) {
  const node = bookmarkStore.nodes[id]
  if (!node || !shareable(node)) return
  if (checkedIds.value.has(id)) {
    checkedIds.value.delete(id)
  } else {
    checkedIds.value.add(id)
  }
  checkedIds.value = new Set(checkedIds.value)
}

// 全量书签供用户"增加"分享范围之外的书签
const allBookmarks = computed<UserLayoutNodeVO[]>(() =>
  Object.values(bookmarkStore.nodes).filter((n) => n.type === HomeItemType.BOOKMARK && n.typeApp)
)

const filterQuery = ref('')
const filteredBookmarks = computed(() => {
  const q = filterQuery.value.trim().toLowerCase()
  if (!q) return allBookmarks.value
  return allBookmarks.value.filter((n) => {
    const app = n.typeApp!
    return app.title?.toLowerCase().includes(q) || app.urlBase?.toLowerCase().includes(q)
  })
})

const note = ref('')
const hasExpiry = ref(false)
const minDate = new Date().toISOString().slice(0, 10)
const expireDate = ref('')

const publishing = ref(false)
const published = ref(false)
const rejectReason = ref('')
const shareId = ref('')
const shareUrl = computed(() => `${runtimeConfig.public.siteUrl}/share/${shareId.value}`)

async function publish() {
  if (checkedIds.value.size === 0 || publishing.value) return
  publishing.value = true
  try {
    const bookmarkUserLinkIds = [...checkedIds.value]
      .map((id) => bookmarkStore.nodes[id]?.typeApp?.bookmarkUserLinkId)
      .filter(Boolean) as string[]
    const res = await shareCreate({
      bookmarkUserLinkIds,
      note: note.value.trim() || undefined,
      expireTime: hasExpiry.value && expireDate.value ? `${expireDate.value}T23:59:59` : null,
    })
    shareId.value = res.id
    if (res.status === ShareStatus.REVIEW_REJECTED) {
      rejectReason.value = res.rejectReason || '内容不符合发布规范'
    }
    published.value = true
    $track('share-create', String(bookmarkUserLinkIds.length))
  } catch (error) {
    $track('share-create-fail')
    console.error('[share/edit] 发布分享失败', error)
  } finally {
    publishing.value = false
  }
}

async function copyLink() {
  try {
    await navigator.clipboard.writeText(shareUrl.value)
    $track('share-copy-link')
    useToastStore().success('链接已复制')
  } catch (error) {
    console.error('[share/edit] 复制链接失败', error)
  }
}
</script>
