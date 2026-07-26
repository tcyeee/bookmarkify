<script lang="ts" setup>
import type { VbenFormSchema } from "@vben/common-ui";
import type { Recordable } from "@vben/types";

import { computed, ref } from "vue";

import { AuthenticationForgetPassword, z } from "@vben/common-ui";

defineOptions({ name: "ForgetPassword" });

const loading = ref(false);

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: "VbenInput",
      componentProps: {
        placeholder: "example@example.com",
      },
      fieldName: "email",
      label: "邮箱",
      rules: z.string().min(1, { message: "请输入邮箱" }).email("邮箱格式错误"),
    },
  ];
});

function handleSubmit(value: Recordable<any>) {
  // eslint-disable-next-line no-console
  console.log("reset email:", value);
}
</script>

<template>
  <AuthenticationForgetPassword :form-schema="formSchema" :loading="loading" @submit="handleSubmit" />
</template>
