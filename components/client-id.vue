<template>
  <div class="bg-green-100 p-10">
    <div class="text-2xl mb-2"> user status</div>
    <div>fingerprint:&emsp;{{ storeUser.fingerprint }}</div>
    <div>clientUid:&emsp;{{ storeUser.clientUid }}</div>
  </div>
</template>

<script lang="ts" setup>
import { StoreUser } from "@/stores/user.store";
const storeUser = StoreUser();

onMounted(() => {});
</script>

<style scoped>
</style>