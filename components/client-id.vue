<template>
  <div class="bg-green-100 p-10">
    <div class="text-2xl mb-2"> user status</div>
    <div>fingerprint:&emsp;{{ storeUser.auth.fingerprint }}</div>
    <div>clientUid:&emsp;{{ storeUser.auth.deviceUid }}</div>
    <div>googleId:&emsp;{{ storeUser.auth.googleId }}</div>
    <div>token:&emsp;{{ storeUser.auth.token }}</div>
    <div>API:&emsp;{{ data.messages }}</div>
    <div>Login:&emsp;{{storeUser.loginStatus  }}</div>

    <div class="flex mt-10 gap-5">
      <div class="cy-btn cy-btn-primary " @click="storeUser.logout">LOGOUT</div>
      <div class="cy-btn cy-btn-primary " @click="storeUser.auth.token='708326e4e40a405994c0cab5f4677835'">ChangeToken</div>
    </div>
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