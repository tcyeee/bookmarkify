<template>
  <div class="bg-green-100 p-10">
    <div class="text-2xl mb-2"> user status</div>
    <div>fingerprint:&emsp;{{ storeUser.fingerprint }}</div>
    <div>clientUid:&emsp;{{ storeUser.clientUid }}</div>
    <div>API:&emsp;{{ data.messages }}</div>
  </div>
</template>

<script lang="ts" setup>
import { StoreUser } from "@/stores/user.store";
import { sysLinkTest } from "~/server/apis";

const storeUser = StoreUser();
const data = reactive({
  messages: "",
});

onMounted(() => {
  test();
});

function test() {
  sysLinkTest().then((res) => {
    data.messages = res;
  });
}
</script>

<style scoped>
</style>