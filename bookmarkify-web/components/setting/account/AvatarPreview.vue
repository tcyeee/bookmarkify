<template>
  <div class="cy-avatar my-10">
    <div class="ring-primary ring-offset-base-100 w-24 rounded-full ring ring-offset-2">
      <img :src="avatarUrl" alt="用户头像" class="w-full h-full rounded-full object-cover" />
    </div>
  </div>
</template>

<script lang="ts" setup>
import { getAvatarUrl } from '@config'

const props = defineProps<{
  avatarPath: string | undefined | null
}>()

const avatarUrl = computed(() => {
  return getAvatarUrl(props.avatarPath)
})
</script>
