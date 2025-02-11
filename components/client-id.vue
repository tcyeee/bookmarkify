<template>
  <div class="bg-green-100 p-10">
    <div class="text-2xl mb-2"> user status</div>
    <div>pinia client-id:&emsp;{{ data.fingerprint }}</div>
    <div class="cy-btn m-10 cy-btn-primary" @click="addOne()">current: {{ storeUser.count }}</div>
  </div>
</template>

<script lang="ts" setup>
import { StoreUser } from "@/stores/user.store";
const storeUser = StoreUser();

let data = reactive({
  clientId: "",
  fingerprint: "",
});

onMounted(() => {
  setClientId();
});

function addOne() {
  
}

function setClientId() {
  data.fingerprint = storeUser.fingerprint;
}
</script>

<style scoped>
</style>