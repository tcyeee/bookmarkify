<script lang="ts" setup>
import type { VbenFormSchema } from "@vben/common-ui";
import type { BasicOption } from "@vben/types";

import { computed, markRaw } from "vue";

import { AuthenticationLogin, SliderCaptcha, z } from "@vben/common-ui";

import { useAuthStore } from "#/store";

defineOptions({ name: "Login" });

const authStore = useAuthStore();

const MOCK_USER_OPTIONS: BasicOption[] = [
  {
    label: "Super",
    value: "vben",
  },
  {
    label: "Admin",
    value: "admin",
  },
  {
    label: "User",
    value: "jack",
  },
];

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: "VbenSelect",
      componentProps: {
        options: MOCK_USER_OPTIONS,
        placeholder: "选择账号",
      },
      fieldName: "selectAccount",
      label: "选择账号",
      rules: z
        .string()
        .min(1, { message: "请选择账号" })
        .optional()
        .default("vben"),
    },
    {
      component: "VbenInput",
      componentProps: {
        placeholder: "请输入用户名",
      },
      dependencies: {
        trigger(values, form) {
          if (values.selectAccount) {
            const findUser = MOCK_USER_OPTIONS.find(
              (item) => item.value === values.selectAccount
            );
            if (findUser) {
              form.setValues({
                password: "123456",
                username: findUser.value,
              });
            }
          }
        },
        triggerFields: ["selectAccount"],
      },
      fieldName: "username",
      label: "用户名",
      rules: z.string().min(1, { message: "请输入用户名" }),
    },
    {
      component: "VbenInputPassword",
      componentProps: {
        placeholder: "密码",
      },
      fieldName: "password",
      label: "密码",
      rules: z.string().min(1, { message: "请输入密码" }),
    },
    {
      component: markRaw(SliderCaptcha),
      fieldName: "captcha",
      rules: z.boolean().refine((value) => value, {
        message: "请完成验证",
      }),
    },
  ];
});
</script>

<template>
  <AuthenticationLogin :form-schema="formSchema" :loading="authStore.loginLoading" @submit="authStore.authLogin" />
</template>
