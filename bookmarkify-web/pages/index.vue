<template>
  <ListPad>
    <div
      v-for="item in items"
      :key="item.value"
      class="flex justify-center items-center border border-gray-300 border-dashed text-white font-semibold"
      :style="{
        width: `${boxSize}px`,
        height: `${boxSize}px`,
        backgroundColor: item.color
      }"
    >
      {{ item.value }}
    </div>
  </ListPad>
</template>

<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import ListPad from '~/components/launch/ListPad.vue'

definePageMeta({ layout: 'launch' })

const boxSize = ref(150)
const items = ref<{ value: number; color: string }[]>([])

onMounted(() => {
  items.value = Array.from({ length: 50 }, (_, idx) => ({
    value: idx + 1,
    color: randomColor()
  }))
})

const randomColor = () => {
  const h = Math.floor(Math.random() * 360)
  const s = 60 + Math.floor(Math.random() * 30) // 60-89%
  const l = 45 + Math.floor(Math.random() * 15) // 45-59%
  return `hsl(${h} ${s}% ${l}%)`
}
</script>
