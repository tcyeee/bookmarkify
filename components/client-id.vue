<template>
  <div class="bg-green-100 p-10">
    <div class="text-2xl mb-2"> user status</div>
    <div>fingerprint:&emsp;{{ storeUser.auth.fingerprint }}</div>
    <div>clientUid:&emsp;{{ storeUser.auth.deviceUid }}</div>
    <div>googleId:&emsp;{{ storeUser.auth.googleId }}</div>
    <div>API:&emsp;{{ data.messages }}</div>
    <div>Login:&emsp;{{storeUser.loginStatus  }}</div>
    <div class="cy-btn cy-btn-primary mt-5" @click="storeUser.logout">LOGOUT</div>
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