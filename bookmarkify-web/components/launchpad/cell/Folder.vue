<template>
  <div class="w-20 flex flex-col items-center" :class="{ 'justify-center': !showTitle }" @click="onClick">
    <div class="w-app h-app rounded-xl bg-gray-300 flex justify-center items-center shadow">
      <div>
        <!-- TODO 文件夹的显示,需要4个LOGO进行填充 -->
        <!-- <div class="flex mb-[0.4rem]">
          <BookmarkLogo v-if="bookmarkList.length > 0" sm :bookmark="bookmarkList[0]!" class="mr-[0.4rem]" />
          <BookmarkLogo v-if="bookmarkList.length > 1" sm :bookmark="bookmarkList[1]!" />
        </div>
        <div class="flex">
          <BookmarkLogo v-if="bookmarkList.length > 2" sm :bookmark="bookmarkList[2]!" class="mr-[0.4rem]" />
          <BookmarkLogo v-if="bookmarkList.length > 3" sm :bookmark="bookmarkList[3]!" />
        </div> -->
      </div>
    </div>
    <div v-if="showTitle" class="w-18 text-xs mt-[0.3rem] text-gray-800 truncate text-center">{{ value.name }}</div>
  </div>
</template>

<script lang="ts" setup>
import { computed, toRefs } from 'vue'
import type { UserLayoutNodeVO } from '@typing'
import { usePreferenceStore } from '@stores/preference.store'

const emit = defineEmits<{ (e: 'open-dir'): void }>()

const props = defineProps<{ value: UserLayoutNodeVO; toggleDrag?: boolean }>()
const { value, toggleDrag } = toRefs(props)

const preferenceStore = usePreferenceStore()
const showTitle = computed<boolean>(() => preferenceStore.preference?.showTitle ?? true)
// const bookmarkList = computed(() => value.value?.bookmarkList ?? [])

function onClick() {
  if (toggleDrag?.value) return
  emit('open-dir')
}
</script>
