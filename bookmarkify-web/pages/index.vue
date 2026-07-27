<template>
  <div class="flex h-screen w-full flex-col">
    <CommonHeader />
    <div class="flex-1 overflow-y-auto bg-white dark:bg-slate-900">
      <div class="max-w-2xl mx-auto px-4 py-6">
        <h1 class="text-xl font-semibold text-slate-900 dark:text-slate-100 mb-4">全部书签</h1>

        <label
          class="cy-input flex items-center gap-2 w-full mb-6"
          :class="query ? 'cy-input-primary' : ''">
          <Icon icon="memory:search" class="size-4 text-slate-400 dark:text-slate-500 shrink-0" />
          <input
            v-model="query"
            type="text"
            class="flex-1 bg-transparent focus:outline-none text-sm"
            placeholder="搜索书签标题、描述或网址..." />
          <button
            v-if="query"
            type="button"
            class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
            @click="query = ''">
            ✕
          </button>
        </label>

        <template v-if="!query">
          <template v-if="bookmarkStore.rootNodes.length === 0">
            <div class="text-sm text-slate-400 dark:text-slate-500 py-10 text-center">暂无书签</div>
          </template>
          <template v-else>
            <BookmarkTreeRow
              v-for="node in bookmarkStore.rootNodes"
              :key="node.id"
              :node="node"
              :depth="0"
              @edit="openEditModal" />
          </template>
        </template>

        <template v-else>
          <div class="mb-6">
            <div class="text-xs font-semibold text-slate-400 dark:text-slate-500 mb-2">
              我的书签 <span v-if="myResults.length">({{ myResults.length }})</span>
            </div>
            <div v-if="myResults.length === 0" class="text-sm text-slate-400 dark:text-slate-500 py-4 text-center">
              没有匹配的书签
            </div>
            <a
              v-for="item in myResults"
              :key="item.id"
              :href="item.typeApp!.urlFull"
              target="_blank"
              rel="noopener noreferrer"
              class="flex items-center gap-3 py-2 px-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors"
              @contextmenu.prevent="onMyResultContextMenu($event, item)">
              <BookmarkLogo :value="item.typeApp!" :size="20" />
              <div class="flex flex-col overflow-hidden flex-1">
                <span class="text-sm text-slate-700 dark:text-slate-200 truncate">
                  {{ item.typeApp!.title || item.typeApp!.urlBase }}
                </span>
                <span class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ item.typeApp!.urlBase }}</span>
              </div>
            </a>
          </div>

          <div>
            <div class="flex items-center gap-2 text-xs font-semibold text-slate-400 dark:text-slate-500 mb-2">
              <span>建议书签</span>
              <span v-if="suggestResults.length">({{ suggestResults.length }})</span>
              <div v-if="isSuggesting" class="size-3 border-2 border-slate-300 border-t-primary rounded-full animate-spin" />
            </div>
            <div v-if="!isSuggesting && suggestResults.length === 0" class="text-sm text-slate-400 dark:text-slate-500 py-4 text-center">
              暂无来自其他用户的匹配书签
            </div>
            <div
              v-for="item in suggestResults"
              :key="item.id"
              class="flex items-center gap-3 py-2 px-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800/60 transition-colors">
              <img v-if="item.logo?.iconBase64" :src="item.logo.iconBase64" class="size-5 rounded shrink-0" />
              <div v-else class="size-5 rounded bg-slate-100 dark:bg-slate-800 center shrink-0">
                <Icon icon="memory:earth" class="size-3 text-slate-400" />
              </div>
              <div class="flex flex-col overflow-hidden flex-1">
                <span class="text-sm text-slate-700 dark:text-slate-200 truncate">
                  {{ item.title || item.appName || item.urlHost }}
                </span>
                <span class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ item.urlHost }}</span>
              </div>
              <button
                type="button"
                class="text-xs font-semibold text-primary shrink-0"
                @click="linkSuggested(item)">
                添加
              </button>
            </div>
          </div>
        </template>
      </div>
    </div>

    <dialog ref="editDialogRef" class="cy-modal">
      <div class="cy-modal-box max-w-md">
        <div class="text-lg font-semibold text-slate-900 dark:text-slate-100 mb-4">修改书签</div>
        <div class="space-y-3">
          <label class="block">
            <span class="text-sm text-slate-600 dark:text-slate-300">书签名称</span>
            <input v-model="editForm.title" type="text" maxlength="150" class="cy-input w-full mt-1" placeholder="请输入书签名称" />
          </label>
          <label class="block">
            <span class="text-sm text-slate-600 dark:text-slate-300">书签描述</span>
            <textarea
              v-model="editForm.description"
              rows="3"
              class="cy-textarea w-full mt-1"
              placeholder="请输入书签描述" />
          </label>
        </div>
        <div class="cy-modal-action">
          <button type="button" class="cy-btn cy-btn-ghost" :disabled="editSaving" @click="closeEditModal">取消</button>
          <button type="button" class="cy-btn cy-btn-primary" :disabled="editSaving" @click="saveEdit">
            {{ editSaving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
      <form method="dialog" class="cy-modal-backdrop">
        <button @click="closeEditModal">close</button>
      </form>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
import { HomeItemType, type UserLayoutNodeVO } from '@typing'
import { bookmarksSearch, bookmarksLinkOne, bookmarksDel, bookmarksUpdate } from '@api'
import { useDebounceFn } from '@vueuse/core'
import ContextMenu from '@imengyu/vue3-context-menu'
import BookmarkLogo from '@/components/launchpad/cell/BookmarkLogo.vue'

definePageMeta({ middleware: 'auth' })

interface BookmarkSearchVO {
  id: string
  urlHost: string
  urlScheme: string
  appName?: string
  title?: string
  logo?: { iconBase64?: string }
}

const bookmarkStore = useBookmarkStore()

onMounted(() => {
  if (!bookmarkStore.isFresh()) bookmarkStore.update()
})

const query = ref('')
const suggestResults = ref<BookmarkSearchVO[]>([])
const isSuggesting = ref(false)

// 用户自己的书签：全部已加载在本地 store，直接客户端过滤即可，无需请求
const myResults = computed<UserLayoutNodeVO[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return []
  return Object.values(bookmarkStore.nodes).filter((node) => {
    if (node.type !== HomeItemType.BOOKMARK || !node.typeApp) return false
    const app = node.typeApp
    return (
      app.title?.toLowerCase().includes(q) ||
      app.description?.toLowerCase().includes(q) ||
      app.urlBase?.toLowerCase().includes(q)
    )
  })
})

// 用户已持有的书签 id 集合，用于从「建议书签」中排除已添加的项
const ownedBookmarkIds = computed(() => {
  const ids = new Set<string>()
  for (const node of Object.values(bookmarkStore.nodes)) {
    if (node.type === HomeItemType.BOOKMARK && node.typeApp?.bookmarkId) ids.add(node.typeApp.bookmarkId)
  }
  return ids
})

const fetchSuggestions = useDebounceFn(async (q: string) => {
  if (!q) {
    suggestResults.value = []
    isSuggesting.value = false
    return
  }
  isSuggesting.value = true
  try {
    const res = await bookmarksSearch(q)
    // 忽略过期请求的结果
    if (q !== query.value.trim()) return
    suggestResults.value = ((res as BookmarkSearchVO[]) || []).filter((item) => !ownedBookmarkIds.value.has(item.id))
  } catch (e) {
    console.error(e)
  } finally {
    if (q === query.value.trim()) isSuggesting.value = false
  }
}, 500)

watch(query, (val) => {
  const q = val.trim()
  if (!q) {
    suggestResults.value = []
    isSuggesting.value = false
    return
  }
  fetchSuggestions(q)
})

async function linkSuggested(item: BookmarkSearchVO) {
  const res = await bookmarksLinkOne(item.id)
  bookmarkStore.addNode(res)
  suggestResults.value = suggestResults.value.filter((i) => i.id !== item.id)
  useToastStore().success('添加成功')
}

// ── 「我的书签」搜索结果右键菜单：修改 / 删除 ──
async function delMyResult(item: UserLayoutNodeVO) {
  try {
    await useConfirmStore().confirm(`确定删除书签「${item.typeApp?.title || item.typeApp?.urlBase}」吗？`, { type: 'warning' })
  } catch {
    return
  }
  try {
    await bookmarksDel([item.id])
    bookmarkStore.removeNode(item.id)
    useToastStore().success('删除成功')
  } catch (error) {
    console.error('[index] 删除书签失败', error)
  }
}

function onMyResultContextMenu(e: MouseEvent, item: UserLayoutNodeVO) {
  if (!item.typeApp) return
  ContextMenu.showContextMenu({
    items: [
      { label: '修改', onClick: () => openEditModal(item) },
      { label: '删除', onClick: () => delMyResult(item) },
    ],
    x: e.x,
    y: e.y,
  })
}

// ── 修改书签弹窗 ──
const editDialogRef = ref<HTMLDialogElement | null>(null)
const editingNode = ref<UserLayoutNodeVO | null>(null)
const editForm = reactive({ title: '', description: '' })
const editSaving = ref(false)

function openEditModal(node: UserLayoutNodeVO) {
  if (!node.typeApp) return
  editingNode.value = node
  editForm.title = node.typeApp.title || ''
  editForm.description = node.typeApp.description || ''
  editDialogRef.value?.showModal()
}

function closeEditModal() {
  editDialogRef.value?.close()
  editingNode.value = null
}

async function saveEdit() {
  const node = editingNode.value
  if (!node?.typeApp || editSaving.value) return
  editSaving.value = true
  try {
    const res = await bookmarksUpdate({
      linkId: node.typeApp.bookmarkUserLinkId,
      title: editForm.title.trim(),
      description: editForm.description.trim(),
    })
    bookmarkStore.nodes[node.id] = { ...node, typeApp: { ...node.typeApp, title: res.title, description: res.description } }
    useToastStore().success('修改成功')
    closeEditModal()
  } catch (error) {
    console.error('[index] 修改书签失败', error)
  } finally {
    editSaving.value = false
  }
}
</script>
