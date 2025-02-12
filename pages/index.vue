<template>
  <client-id></client-id>
</template>

<script lang="ts" setup>
// @ts-ignore
import { useOneTap, type CredentialResponse } from "vue3-google-signin";
import { Environments } from "~/types/environments";

// 仅在生产环境进行谷歌登陆
if (process.env.NODE_ENV == Environments.PRO) {
  useOneTap({
    onSuccess: (response: CredentialResponse) => {
      console.log("Success:", response.credential);
    },
    onError: () => console.error("Error with One Tap Login"),
  });
}
</script>

<style>
</style>