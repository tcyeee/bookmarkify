<template>
  <NuxtRouteAnnouncer />
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
</template>
<script setup>
onMounted(() => webSocketInit());

function webSocketInit() {
  const webSocketStore = useWebSocketStore();
  webSocketStore.connect();
}
</script>