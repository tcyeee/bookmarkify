<template>
  <ListPad>
    <div      v-for="i in 50"
      :key="i"
      class="flex justify-center items-center bg-white border border-gray-300 border-dashed"
      :style="{
        width: `${boxSize}px`,
        height: `${boxSize}px`
      }"
    >
      {{ i }}
    </div>
  </ListPad>
</template>

<script lang="ts" setup>
import { ref } from 'vue'
import ListPad from '~/components/launch/ListPad.vue'

definePageMeta({ layout: 'launch' })

const boxSize = ref(150)
</script>
