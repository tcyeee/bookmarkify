<script setup lang="ts">
import { useAppConfig } from '@vben/hooks';
import {
  SvgGithubIcon,
  SvgGoogleIcon,
  SvgQQChatIcon,
  SvgWeChatIcon,
} from '@vben/icons';

import { VbenIconButton } from '@vben-core/shadcn-ui';

import DingdingLogin from './dingding-login.vue';

defineOptions({
  name: 'ThirdPartyLogin',
});

const {
  auth: { dingding: dingdingAuthConfig },
} = useAppConfig(import.meta.env, import.meta.env.PROD);
</script>

<template>
  <div class="w-full sm:mx-auto md:max-w-md">
    <div class="mt-4 flex items-center justify-between">
      <span class="border-input w-[35%] border-b dark:border-gray-600"></span>
      <span class="text-muted-foreground text-center text-xs uppercase">
        {{ '其他登录方式' }}
      </span>
      <span class="border-input w-[35%] border-b dark:border-gray-600"></span>
    </div>

    <div class="mt-4 flex flex-wrap justify-center">
      <VbenIconButton
        :tooltip="'微信登录'"
        tooltip-side="top"
        class="mb-3"
      >
        <SvgWeChatIcon />
      </VbenIconButton>
      <VbenIconButton
        :tooltip="'QQ登录'"
        tooltip-side="top"
        class="mb-3"
      >
        <SvgQQChatIcon />
      </VbenIconButton>
      <VbenIconButton
        :tooltip="'Github登录'"
        tooltip-side="top"
        class="mb-3"
      >
        <SvgGithubIcon />
      </VbenIconButton>
      <VbenIconButton
        :tooltip="'Google登录'"
        tooltip-side="top"
        class="mb-3"
      >
        <SvgGoogleIcon />
      </VbenIconButton>
      <DingdingLogin
        v-if="dingdingAuthConfig"
        :corp-id="dingdingAuthConfig.corpId"
        :client-id="dingdingAuthConfig.clientId"
        class="mb-3"
      />
    </div>
  </div>
</template>
