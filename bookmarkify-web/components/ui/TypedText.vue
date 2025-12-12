<template>
  <span class="sui-typed-text" ref="typedElement" v-bind="$attrs"></span>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import Typed from 'typed.js'

const props = defineProps({
  words: {
    type: Array,
    required: true,
  },
  typeSpeed: {
    type: Number,
    default: 150,
  },
})

const typed = ref(null)
const typedElement = ref(null)

onMounted(() => {
  typed.value = new Typed(typedElement.value, {
    strings: props.words,
    typeSpeed: props.typeSpeed,
    loop: true,
  })
})

onUnmounted(() => {
  if (typed.value) {
    typed.value?.destory()
    typed.value = null
    typedElement.value = null
  }
})
</script>

<style scoped></style>
