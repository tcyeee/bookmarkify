<template>
  <div>
    <h1>welcome</h1>
  </div>
</template>

<script lang="ts" setup>
definePageMeta({
  middleware: 'auth',
  layout: 'default',
})
</script>

<style scoped></style>
