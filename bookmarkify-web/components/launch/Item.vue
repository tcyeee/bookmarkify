<template>
 <LaunchpadCellBookmark
                v-if="item.type === HomeItemType.BOOKMARK"
                :value="item.typeApp as BookmarkShow"
                :show-title="showTitle"
                @click="openPage(item.typeApp)" /> 


                  <!-- <div
    class="flex w-(--cell-size) flex-col items-center border border-gray-300 border-dashed"
    @click="handleClick">
    <div
      class="flex h-(--cell-size) w-(--cell-size) select-none items-center justify-center border-4 border-gray-300 rounded-3xl text-4xl font-bold text-white shadow opacity-80"
      :style="{ backgroundColor: item.color }">
      {{ item.value }}
    </div>
    <div
      class="mt-1 flex h-(--title-height) w-full items-center justify-center rounded bg-gray-200 text-sm font-semibold text-gray-600">
      APP-{{ item.value }}
    </div>
  </div> -->



              <!-- <LaunchpadCellFolder
                v-if="item.type === HomeItemType.BOOKMARK_DIR"
                :value="toBookmarkDir(item)"
                :show-title="showTitle"                />
              <LaunchpadCellBookmark
                v-else-if="item.type === HomeItemType.BOOKMARK"
                :value="item.typeApp"
                :show-title="showTitle"
                @click="openPage(item.typeApp)" />
              <LaunchpadCellBookmarkLoading
                v-else-if="item.type === HomeItemType.BOOKMARK_LOADING"
                :show-title="showTitle" /> -->

</template>

<script setup lang="ts">
import { HomeItemType, type UserLayoutNodeVO } from '@typing'
const props = defineProps<{ item: UserLayoutNodeVO }>()
</script>