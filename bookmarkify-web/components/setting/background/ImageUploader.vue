<template>
  <div class="space-y-3">
    <div class="flex flex-wrap items-center gap-3">
      <label class="cy-btn cy-btn-soft flex items-center gap-2 cursor-pointer">
        <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="$emit('file-change', $event)" />
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path
            stroke-linecap="round"
            stroke-linejoin="round"
            stroke-width="2"
            d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
        </svg>
        <span>选择图片</span>
      </label>

      <button v-if="previewUrl" type="button" class="cy-btn cy-btn-accent" :disabled="uploading" @click="$emit('upload')">
        <span v-if="uploading">上传中...</span>
        <span v-else>确认上传</span>
      </button>

      <button v-if="previewUrl" type="button" class="cy-btn cy-btn-ghost" :disabled="uploading" @click="$emit('cancel')">
        取消
      </button>

      <button
        v-if="backgroundPath && !previewUrl"
        type="button"
        class="cy-btn cy-btn-ghost"
        :disabled="uploading"
        @click="$emit('reset')">
        恢复默认
      </button>
    </div>
    <p class="text-xs text-slate-500">仅支持图片文件，大小限制见上传配置</p>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import type { Ref } from 'vue'

const props = defineProps<{
  previewUrl: string | null
  backgroundPath?: string | null
  uploading: boolean
  fileInputRef?: Ref<HTMLInputElement | null | undefined>
}>()

// 允许父组件传入外部的 fileInputRef，未传入则使用本地 ref
const fileInputRef = props.fileInputRef ?? ref<HTMLInputElement>()
</script>
