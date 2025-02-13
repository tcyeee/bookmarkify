<template>
  <NuxtPage />
</template>
<script  lang="ts" setup>
import { Environments } from "~/types/environments";
import { StoreUser } from "@/stores/user.store";
const storeUser = StoreUser();

onMounted(() => {
  googleLogin();
});

function googleLogin() {
  if (process.env.NODE_ENV != Environments.PRO) return;
  if (storeUser.auth.googleId) return;
}
</script>
<style>
</style>