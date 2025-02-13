<template>
  <NuxtRouteAnnouncer />
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
</template>
<script  lang="ts" setup>
// @ts-ignore
import { useOneTap, type CredentialResponse } from "vue3-google-signin";
import { Environments } from "~/types/environments";
import { StoreUser } from "@/stores/user.store";
const storeUser = StoreUser();

onMounted(() => {
  googleLogin();
});

function googleLogin() {
  console.log("== 01" + process.env.NODE_ENV);
  if (process.env.NODE_ENV != Environments.PRO) return;
  console.log("== 02" + storeUser.auth.googleId);
  if (storeUser.auth.googleId) return;
  console.log("== 03");
  useOneTap({
    onSuccess: (response: CredentialResponse) => {
      const jwt = response.credential; // 获取 JWT
      const base64Url = jwt.split(".")[1]; // 获取负载部分
      const base64 = base64Url.replace(/-/g, "+").replace(/_/g, "/");
      const decodedData = JSON.parse(window.atob(base64)); // 解码负载
      console.log(decodedData); // 打印解码后的数据
    },
    onError: () => ElNotification.error("谷歌API调用失败!"),
  });
}
</script>