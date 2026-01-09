<template>
  <div class="flex flex-wrap">
    <!-- 这是一个带有边框的正方形,边长可以在代码中设置 -->
    <div
      v-for="i in 50"
      :key="i"
      class="flex justify-center items-center bg-white border border-gray-300 border-dashed shrink-0"
      :style="{
        width: `${boxSize}px`,
        height: `${boxSize}px`
      }"
    >
      {{ i }}
    </div>
  </div>
</template>

<script lang="ts" setup>
import { ref } from 'vue'

definePageMeta({ layout: 'launch' })

const boxSize = ref(150)
</script>
