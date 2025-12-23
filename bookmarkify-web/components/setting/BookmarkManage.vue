<template>
  <div>
    <h3 class="setting-section-title">导入 Chrome 书签</h3>
    <p class="setting-section-desc">支持从 Chrome 导出的 HTML 书签文件，一次性导入你的所有书签。</p>

    <div class="import-card">
      <div class="import-actions">
        <label class="cy-btn cy-btn-soft cursor-pointer">
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

      <div class="import-file-info">
        <span v-if="selectedFile">已选择：{{ selectedFile.name }}</span>
        <span v-else>尚未选择文件</span>
      </div>

      <ul class="import-hints">
        <li>打开 Chrome 设置 &gt; 书签管理器 &gt; 右上角「⋮」&gt; 导出书签。</li>
        <li>将导出的 `.html` 文件在此处选择并导入。</li>
        <li>导入过程中不会覆盖你已有的书签，仅做合并。</li>
      </ul>

      <div v-if="statusMessage" class="import-status" :class="statusClass">
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

const statusClass = computed(() => {
  return {
    'import-status-success': statusType.value === 'success',
    'import-status-error': statusType.value === 'error',
  }
})

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

<style scoped>
.setting-section-title {
  font-size: 1.25rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
  color: #1f2937;
}

.setting-section-desc {
  font-size: 0.875rem;
  color: #6b7280;
  margin-bottom: 1.5rem;
}

.import-card {
  background-color: #ffffff;
  border-radius: 0.75rem;
  padding: 1.5rem;
}

.import-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: center;
}

.import-file-info {
  margin-top: 0.75rem;
  font-size: 0.875rem;
  color: #4b5563;
}

.import-hints {
  margin-top: 1rem;
  padding-left: 1.1rem;
  font-size: 0.875rem;
  color: #6b7280;
  list-style-type: disc;
}

.import-hints li + li {
  margin-top: 0.25rem;
}

.import-status {
  margin-top: 1rem;
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  font-size: 0.875rem;
  background-color: #f9fafb;
  color: #374151;
}

.import-status-success {
  background-color: #ecfdf3;
  color: #166534;
}

.import-status-error {
  background-color: #fef2f2;
  color: #b91c1c;
}
</style>
