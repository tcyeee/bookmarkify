<template>
  <div
    class="w-full max-w-[420px] mx-auto rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800/40 p-4">
    <div class="flex items-center gap-2 mb-2">
      <Icon
        :icon="isRoot ? 'memory:home-thatched' : 'memory:folder'"
        class="size-4 shrink-0"
        :class="isRoot ? 'text-slate-400 dark:text-slate-500' : 'text-amber-500'" />
      <span class="text-sm font-semibold text-slate-700 dark:text-slate-200 truncate">{{ name }}</span>
      <span v-if="children.length" class="text-xs text-slate-400 dark:text-slate-500">({{ children.length }})</span>
    </div>

    <div v-if="children.length === 0" class="text-xs text-slate-400 dark:text-slate-500 py-3 text-center">暂无书签</div>
    <template v-else>
      <BookmarkTreeRow
        v-for="child in children"
        :key="child.id"
        :node="child"
        :depth="0"
        @edit="(n: UserLayoutNodeVO) => emit('edit', n)" />
    </template>
  </div>
</template>

<script lang="ts" setup>
import type { UserLayoutNodeVO } from '@typing'
import BookmarkTreeRow from '@/components/BookmarkTreeRow.vue'

defineOptions({ name: 'BookmarkFolderCard' })

defineProps<{ name: string; isRoot: boolean; children: UserLayoutNodeVO[] }>()
const emit = defineEmits<{ edit: [node: UserLayoutNodeVO] }>()
</script>
