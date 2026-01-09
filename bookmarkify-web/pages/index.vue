<template>
  <ListPad>
    <div
      v-for="item in items"
      :key="item"
      class="flex justify-center items-center bg-white border border-gray-300 border-dashed"
      :style="{
        width: `${boxSize}px`,
        height: `${boxSize}px`
      }"
    >
      {{ item }}
    </div>
  </ListPad>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import ListPad from '~/components/launch/ListPad.vue'

definePageMeta({ layout: 'launch' })

const boxSize = ref(150)
const items = ref<number[]>([])

onMounted(() => {
  items.value = Array.from({ length: 50 }, (_, idx) => idx + 1)
})
</script>
