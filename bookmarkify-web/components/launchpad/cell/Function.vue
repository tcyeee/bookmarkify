<template>
  <div class="w-20 flex flex-col items-center" :class="{ 'justify-center': !showTitle }">
    <div class="w-app h-app rounded-xl bg-slate-900/80 flex justify-center items-center shadow">
      <span class="icon--memory-dot-hexagon-fill icon-size-30 text-white" />
    </div>
    <div v-if="showTitle" class="w-18 text-sm mt-[0.3rem] text-white opacity-90 truncate text-center">
      {{ label }}
    </div>
  </div>
</template>

<script lang="ts" setup>
import { toRefs } from 'vue'

const props = withDefaults(
  defineProps<{
    label?: string
    showTitle?: boolean
  }>(),
  {
    label: '设置',
    showTitle: true,
  },
)

const { label, showTitle } = toRefs(props)
</script>

