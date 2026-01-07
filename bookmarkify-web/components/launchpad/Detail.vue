<template>
  <div v-if="detail" class="space-y-4">
    <div class="text-[1.4rem] font-semibold">书签信息</div>
    <div class="text-sm text-slate-500 break-all">{{ detail.urlFull }}</div>

    <div class="flex items-center gap-4">
      <div class="h-20 w-20 rounded-2xl bg-white flex justify-center items-center shadow-lg overflow-hidden">
        <img
          v-if="detail.iconHdUrl && !hdError"
          :src="detail.iconHdUrl"
          alt="bookmark icon"
          class="w-full h-full object-contain"
          @error="onHdError"
        />
        <img
          v-else-if="!iconError"
          class="w-10 h-10"
          :src="iconBase64"
          alt="bookmark icon"
          @error="onIconError"
        />
        <img v-else class="w-10 h-10" src="/avatar/default.png" alt="bookmark icon" />
      </div>
      <div class="text-xs text-slate-400 break-all">{{ detail.urlBase }}</div>
    </div>

    <ActionInput
      v-model="form.title"
      label="书签名称"
      placeholder="请输入书签名称"
      :max-length="150"
      :dirty="isDirty"
      :busy="saving"
      :disabled="saving"
      primary-text="保存"
      primary-loading-text="保存中..."
      secondary-text="取消"
      @primary="save"
      @secondary="resetForm"
    />

    <div>
      <span class="cy-label-text block mb-2">书签描述</span>
      <div
        class="rounded-xl border border-slate-200 bg-linear-to-br from-slate-50 via-white to-slate-100 px-4 py-3 text-sm text-slate-700 leading-relaxed min-h-[96px] whitespace-pre-wrap shadow-sm dark:border-slate-700 dark:from-slate-900 dark:via-slate-900 dark:to-slate-800 dark:text-slate-100">
        {{ detail.description || '暂无描述' }}
      </div>
    </div>

  </div>
</template>

<script lang="ts" setup>
import type { BookmarkShow, BookmarkUpdatePrams } from '@typing'
import { bookmarksUpdate } from '@api'
import { computed, reactive, ref, watch } from 'vue'
import ActionInput from '../common/ActionInput.vue'

const props = defineProps<{ data: BookmarkShow | null }>()

const detail = computed(() => props.data)
const saving = ref(false)
const form = reactive({ title: '' })
const isDirty = computed(() => form.title.trim() !== (detail.value?.title || ''))

const hdError = ref(false)
const iconError = ref(false)
const iconBase64 = computed(() => `data:image/png;base64,${detail.value?.iconBase64 || ''}`)

watch(
  () => detail.value,
  (val) => {
    form.title = val?.title || ''
    hdError.value = false
    iconError.value = false
  },
  { immediate: true }
)

async function save() {
  if (!detail.value || saving.value || !isDirty.value) return

  const params: BookmarkUpdatePrams = {
    linkId: detail.value.bookmarkUserLinkId,
    title: form.title.trim(),
    description: detail.value.description?.trim() || '',
  }

  saving.value = true
  try {
    const res = await bookmarksUpdate(params)
    detail.value.title = res.title
    detail.value.description = res.description
  } catch (error) {
    console.error('更新书签失败', error)
  } finally {
    saving.value = false
  }
}

function resetForm() {
  if (!detail.value) return
  form.title = detail.value.title || ''
}

function onHdError() {
  hdError.value = true
}

function onIconError() {
  iconError.value = true
}
</script>

<style></style>
