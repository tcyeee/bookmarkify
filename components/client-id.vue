<template>
  <div class="bg-green-100 p-10">
    <div class="text-2xl mb-2"> user status</div>
    <div>Last client-id:&emsp;9f4dbdc2431838fa0cebe88d736e4661</div>
    <div>You client-id:&emsp;{{ data.clientId }}</div>
  </div>
</template>

<script lang="ts" setup>
let data = reactive({
  clientId: "",
});

onMounted(() => {
  setClientId();
});

function setClientId() {
  const userFingerprint = useState("userFingerprint");
  data.clientId = String(userFingerprint.value);
}
</script>

<style scoped>
</style>