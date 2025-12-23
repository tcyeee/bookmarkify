<template>
  <div class="cy-avatar">
    <div class="ring-primary ring-offset-base-100 w-24 h-24 rounded-full ring ring-offset-2 overflow-hidden bg-slate-100">
      <img
        v-if="showImage"
        :src="avatarUrl"
        alt="用户头像"
        class="h-full w-full rounded-full object-cover"
        @error="loadError = true" />
      <div
        v-else
        class="flex h-full w-full items-center justify-center rounded-full bg-slate-200 text-slate-700 text-2xl font-semibold select-none">
        {{ fallbackInitial }}
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { getAvatarUrl } from '@config'

const props = defineProps<{
  avatarPath: string | undefined | null
  fallbackText?: string
}>()

const avatarUrl = computed(() => {
  return getAvatarUrl(props.avatarPath)
})

const loadError = ref(false)
const showImage = computed(() => !!props.avatarPath && !loadError.value)
const fallbackInitial = computed(() => (props.fallbackText || '用').trim().slice(0, 1) || '用')
</script>
