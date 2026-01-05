<template>
  <div>
    <div
      class="h-20 w-20 rounded-2xl bg-white flex justify-center items-center border border-gray-100 shadow-sm hover:shadow-lg"
      @click="sysStore.addBookmarkDialogVisible = true">
      <span class="icon--add icon-size-35 text-gray-400" />
    </div>

    <AddOneDialog @success="handleSuccess" />
  </div>
</template>

<script lang="ts" setup>
import type { HomeItem } from '@typing'
import AddOneDialog from './AddOneDialog.vue'

const sysStore = useSysStore()

const emit = defineEmits<{ (e: 'success', res: HomeItem): void }>()

function handleSuccess(res: HomeItem) {
  emit('success', res)
}
</script>
