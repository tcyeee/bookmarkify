<script lang="ts" setup>
import type { VbenFormSchema } from "@vben/common-ui";
import type { Recordable } from "@vben/types";

import { computed, ref } from "vue";

import { AuthenticationCodeLogin, z } from "@vben/common-ui";

defineOptions({ name: "CodeLogin" });

const loading = ref(false);
const CODE_LENGTH = 6;

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      component: "VbenInput",
      componentProps: {
        placeholder: "手机号",
      },
      fieldName: "phoneNumber",
      label: "手机号",
      rules: z
        .string()
        .min(1, { message: "请输入手机号" })
        .refine((v) => /^\d{11}$/.test(v), {
          message: "手机号格式错误",
        }),
    },
    {
      component: "VbenPinInput",
      componentProps: {
        codeLength: CODE_LENGTH,
        createText: (countdown: number) => {
          const text =
            countdown > 0 ? `${countdown}秒后重新获取` : "获取验证码";
          return text;
        },
        placeholder: "验证码",
      },
      fieldName: "code",
      label: "验证码",
      rules: z.string().length(CODE_LENGTH, {
        message: `请输入${CODE_LENGTH}位验证码`,
      }),
    },
  ];
});
/**
 * 异步处理登录操作
 * Asynchronously handle the login process
 * @param values 登录表单数据
 */
async function handleLogin(values: Recordable<any>) {
  // eslint-disable-next-line no-console
  console.log(values);
}
</script>

<template>
  <AuthenticationCodeLogin :form-schema="formSchema" :loading="loading" @submit="handleLogin" />
</template>
