<template>
  <div class="space-y-2 text-slate-900 dark:text-slate-100 transition-colors">
    <h3 class="pb-5 text-xl font-semibold text-slate-900 dark:text-slate-100 transition-colors">导入 Chrome 书签</h3>

    <div class="bg-white dark:bg-slate-950/80 transition-colors">
      <div class="flex flex-wrap items-center gap-3">
        <label class="cy-btn cy-btn-soft cursor-pointer flex items-center gap-2">
          <input ref="fileInputRef" type="file" accept=".html,.htm" class="hidden" @change="handleFileChange" />
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-8l-4-4m0 0L8 8m4-4v12" />
          </svg>
          <span>选择书签文件</span>
        </label>

        <button class="cy-btn cy-btn-accent" :disabled="!selectedFile || importing" @click="handleImport">
          <span v-if="importing">导入中...</span>
          <span v-else>开始导入</span>
        </button>

        <button v-if="selectedFile" class="cy-btn cy-btn-ghost" :disabled="importing" @click="handleReset">清除选择</button>
      </div>

      <div class="mt-3 text-sm text-slate-600 dark:text-slate-300 transition-colors">
        <span v-if="selectedFile">已选择：{{ selectedFile.name }}</span>
        <span v-else>尚未选择文件</span>
      </div>

      <ul
        class="mt-4 pl-5 list-disc space-y-1 text-sm text-slate-600 dark:text-slate-300 transition-colors marker:text-slate-500 dark:marker:text-slate-400">
        <li>打开 Chrome 设置 &gt; 书签管理器 &gt; 右上角「⋮」&gt; 导出书签。</li>
        <li>将导出的 `.html` 文件在此处选择并导入。</li>
        <li>导入过程中不会覆盖你已有的书签，仅做合并。</li>
      </ul>

      <div v-if="statusMessage" class="mt-4 rounded-md px-4 py-3 text-sm transition-colors" :class="statusClass">
        {{ statusMessage }}
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
const fileInputRef = ref<HTMLInputElement>()
const selectedFile = ref<File | null>(null)
const importing = ref(false)
const statusMessage = ref('')
const statusType = ref<'default' | 'success' | 'error'>('default')

function handleFileChange(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]

  statusMessage.value = ''
  statusType.value = 'default'

  if (!file) {
    selectedFile.value = null
    return
  }

  // 只简单校验后缀/类型，详细校验交给后端
  if (!file.name.endsWith('.html') && !file.name.endsWith('.htm')) {
    ElMessage.warning('请上传从 Chrome 导出的 HTML 书签文件')
    selectedFile.value = null
    if (fileInputRef.value) {
      fileInputRef.value.value = ''
    }
    return
  }

  selectedFile.value = file
}

function handleReset() {
  selectedFile.value = null
  statusMessage.value = ''
  statusType.value = 'default'
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
  }
}

const statusClass = computed(() =>
  statusType.value === 'success'
    ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/40 dark:text-emerald-200'
    : statusType.value === 'error'
    ? 'bg-rose-50 text-rose-700 dark:bg-rose-900/40 dark:text-rose-200'
    : 'bg-slate-100 text-slate-800 dark:bg-slate-800 dark:text-slate-100'
)

async function handleImport() {
  if (!selectedFile.value) {
    ElMessage.warning('请先选择要导入的书签文件')
    return
  }

  importing.value = true
  statusMessage.value = '正在导入书签，请稍候...'
  statusType.value = 'default'

  try {
    // TODO: 在此处接入后端导入 API，将 selectedFile 作为 FormData 上传
    // const formData = new FormData()
    // formData.append('file', selectedFile.value)
    // await importBookmarks(formData)

    // 这里先做个前端假等待，等你接后端
    await new Promise((resolve) => setTimeout(resolve, 1000))

    statusMessage.value = '导入完成！你可以在主页查看新书签。'
    statusType.value = 'success'
  } catch (error: any) {
    console.error(error)
    statusMessage.value = error?.message || '导入失败，请稍后重试。'
    statusType.value = 'error'
  } finally {
    importing.value = false
  }
}
</script>
