<template>
  <div class="flex h-screen w-full flex-col">
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

          <div class="text-sm text-slate-400 dark:text-slate-500 py-10 text-center">helloworld</div>
        </template>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import type { UserLayoutNodeVO } from '@typing'
import BookmarkLogo from '@/components/launchpad/cell/BookmarkLogo.vue'

definePageMeta({ middleware: 'auth' })

const route = useRoute()
const bookmarkStore = useBookmarkStore()

onMounted(() => {
  if (bookmarkStore.isFresh()) return
  bookmarkStore.update().catch((error) => console.error('[bookmark/similar] 书签刷新失败', error))
})

const node = computed<UserLayoutNodeVO | undefined>(() => {
  const id = route.query.id
  if (typeof id !== 'string') return undefined
  return bookmarkStore.nodes[id]
})
</script>
