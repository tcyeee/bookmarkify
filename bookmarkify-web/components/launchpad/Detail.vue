<template>
  <div v-if="detail" class="space-y-4">
    <div class="text-[1.4rem] font-semibold">书签信息</div>
    <div class="text-sm text-slate-500 break-all">{{ detail.urlFull }}</div>

    <div class="flex items-center gap-4">
      <img :src="iconSrc" class="w-20 h-20 rounded-2xl shadow-lg" />
      <div class="text-xs text-slate-400 break-all">{{ detail.urlBase }}</div>
    </div>

    <div>
      <span class="cy-label-text block mb-2">书签名称</span>
      <input
        v-model="form.title"
        type="text"
        maxlength="150"
        :disabled="saving"
        placeholder="请输入书签名称"
        class="mt-1 h-12 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm focus:border-slate-400 focus:outline-none focus:ring-2 focus:ring-slate-200 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:placeholder:text-slate-500 dark:focus:border-slate-500 dark:focus:ring-slate-600"
        @blur="save"
      />
    </div>

    <div>
      <span class="cy-label-text block mb-2">书签描述</span>
      <textarea
        v-model="form.description"
        maxlength="2000"
        rows="4"
        :disabled="saving"
        placeholder="请输入书签描述"
        class="textarea textarea-bordered w-full min-h-[120px] focus:outline-none"
        @blur="save"
      />
    </div>

    <div class="flex justify-end">
      <button class="btn btn-primary btn-sm" :disabled="saving" @click="save">
        {{ saving ? '保存中...' : '保存' }}
      </button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import type { Bookmark, BookmarkUpdatePrams } from '@typing'
import { bookmarksUpdate } from '@api'
import { computed, reactive, ref, watch } from 'vue'

const props = defineProps<{ data: Bookmark | null }>()

const detail = computed(() => props.data)
const saving = ref(false)
const form = reactive({
  title: '',
  description: '',
})

const iconSrc = computed(
  () => detail.value?.iconHdUrl || (detail.value as any)?.iconUrlFull || detail.value?.iconBase64 || ''
)

watch(
  () => detail.value,
  (val) => {
    form.title = val?.title || ''
    form.description = val?.description || ''
  },
  { immediate: true }
)

async function save() {
  if (!detail.value || saving.value) return

  const params: BookmarkUpdatePrams = {
    linkId: detail.value.bookmarkUserLinkId,
    title: form.title.trim(),
    description: form.description.trim(),
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
</script>

<style></style>
