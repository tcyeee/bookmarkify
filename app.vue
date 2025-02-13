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
  if (process.env.NODE_ENV != Environments.PRO) return;
  if (storeUser.auth.googleId) return;
  useOneTap({
    onSuccess: (response: CredentialResponse) => {
      console.log("======================[google]==========");
      console.log(response);
      console.log(response.credential);
      console.log("======================[google]==========");
    },
    onError: () => ElNotification.error("谷歌API调用失败!"),
  });
}
</script>