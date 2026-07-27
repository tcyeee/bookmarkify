<template>
  <div class="max-w-2xl mx-auto px-4 py-10">
    <div v-if="pending" class="animate-pulse space-y-3">
      <div class="h-6 w-40 rounded bg-slate-200 dark:bg-slate-700" />
      <div class="h-4 w-full rounded bg-slate-200 dark:bg-slate-700" />
      <div v-for="i in 5" :key="i" class="h-10 w-full rounded bg-slate-200 dark:bg-slate-700" />
    </div>

    <div v-else-if="error || !data" class="flex flex-col items-center gap-3 py-20 text-center">
      <Icon icon="mdi:link-off" class="size-10 text-slate-300 dark:text-slate-600" />
      <p class="text-sm text-slate-400 dark:text-slate-500">该分享已失效或不存在</p>
    </div>

    <template v-else>
      <!-- 分享人信息 -->
      <div class="flex items-center gap-3 mb-6">
        <img v-if="data.sharer.avatarUrl" :src="data.sharer.avatarUrl" class="size-10 rounded-full object-cover" />
        <div v-else class="size-10 rounded-full bg-slate-100 dark:bg-slate-800 center">
          <Icon icon="mdi:account" class="size-6 text-slate-400" />
        </div>
        <div>
          <p class="text-sm font-semibold text-slate-900 dark:text-slate-100">{{ data.sharer.nickName }}</p>
          <p class="text-xs text-slate-400 dark:text-slate-500">分享了 {{ data.bookmarks.length }} 个书签</p>
        </div>
      </div>

      <!-- 分享文案 -->
      <p v-if="data.note" class="text-sm text-slate-600 dark:text-slate-300 whitespace-pre-wrap mb-6">{{ data.note }}</p>

      <!-- 书签列表 -->
      <div v-if="data.bookmarks.length === 0" class="text-sm text-slate-400 dark:text-slate-500 py-10 text-center">暂无书签</div>
      <div v-else class="rounded-xl border border-slate-200 dark:border-slate-700 divide-y divide-slate-100 dark:divide-slate-800 overflow-hidden">
        <a
          v-for="item in data.bookmarks"
          :key="item.bookmarkUserLinkId"
          :href="item.urlFull"
          target="_blank"
          rel="noopener noreferrer"
          class="flex items-center gap-3 px-4 py-3 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors">
          <BookmarkLogo :value="item" :size="20" />
          <div class="flex flex-col overflow-hidden flex-1">
            <span class="text-sm text-slate-700 dark:text-slate-200 truncate">{{ item.title || item.urlBase }}</span>
            <span class="text-xs text-slate-400 dark:text-slate-500 truncate">{{ item.urlBase }}</span>
          </div>
        </a>
      </div>
    </template>
  </div>
</template>

<script lang="ts" setup>
import { shareView } from '@api'
import BookmarkLogo from '@/components/launchpad/cell/BookmarkLogo.vue'

definePageMeta({ layout: 'explore' })

const route = useRoute()
const code = route.params.code as string

const { data, error, pending } = await useAsyncData(`share-view-${code}`, () => shareView(code))

useHead({ title: data.value ? `${data.value.sharer.nickName} 的书签分享` : '书签分享' })
</script>
