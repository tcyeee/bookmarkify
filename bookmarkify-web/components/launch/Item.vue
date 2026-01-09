<template>
            <div
              class="flex w-(--cell-size) flex-col items-center border border-gray-300 border-dashed"
              @click="onItemClick(item)">
              <div
                class="flex h-(--cell-size) w-(--cell-size) select-none items-center justify-center border-4 border-gray-300 rounded-3xl text-4xl font-bold text-white shadow opacity-80"
                :style="{ backgroundColor: item.color }">
                {{ item.value }}
              </div>
              <div
                class="mt-1 flex h-(--title-height) w-full items-center justify-center rounded bg-gray-200 text-sm font-semibold text-gray-600">
                APP-{{ item.value }}
              </div>
            </div>
</template>

<script setup lang="ts">
function onItemClick(item: any) {
  console.log(`点击 APP：APP-${item.value}`)
}
</script>

<style scoped>

</style>