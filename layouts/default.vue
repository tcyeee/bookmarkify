<template>
  <NuxtPage />
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
  console.log("[google]======start");

  useOneTap({
    onSuccess: (response: CredentialResponse) => {
      console.log("======================[google]==========");
      console.log(response);
      console.log(response.credential);
      console.log("======================[google]==========");
    },
    onError: () => console.error("Error with One Tap Login"),
  });
}
</script>
<style>
</style>