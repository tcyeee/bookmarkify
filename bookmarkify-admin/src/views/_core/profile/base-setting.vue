<script setup lang="ts">
import type { UploadRequestOptions } from 'element-plus';

import type { VbenFormSchema } from '#/adapter/form';

import { computed, onMounted, ref } from 'vue';

import { ProfileBaseSetting } from '@vben/common-ui';
import { preferences } from '@vben/preferences';
import { useUserStore } from '@vben/stores';

import { ElAvatar, ElMessage, ElUpload } from 'element-plus';

import { getUserInfoApi, updateUserInfoApi, uploadAvatarApi } from '#/api';
import { useAuthStore } from '#/store';

const userStore = useUserStore();
const authStore = useAuthStore();

const profileBaseSettingRef = ref();
const uploading = ref(false);

const avatarUrl = computed(
  () => userStore.userInfo?.avatar || preferences.app.defaultAvatar,
);

const formSchema = computed((): VbenFormSchema[] => {
  return [
    {
      fieldName: 'nickName',
      component: 'Input',
      label: '昵称',
      rules: 'required',
    },
  ];
});

onMounted(async () => {
  const data = await getUserInfoApi();
  profileBaseSettingRef.value.getFormApi().setValues(data);
});

function beforeAvatarUpload(file: File) {
  if (!file.type.startsWith('image/')) {
    ElMessage.error('请选择图片文件');
    return false;
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片大小不能超过 5MB');
    return false;
  }
  return true;
}

async function customUploadAvatar(options: UploadRequestOptions) {
  uploading.value = true;
  try {
    await uploadAvatarApi(options.file as File);
    await authStore.fetchUserInfo();
    ElMessage.success('头像更新成功');
  } catch (error) {
    options.onError?.(error as any);
  } finally {
    uploading.value = false;
  }
}

async function handleSubmit(values: Record<string, any>) {
  await updateUserInfoApi(values.nickName);
  await authStore.fetchUserInfo();
  ElMessage.success('昵称更新成功');
}
</script>
<template>
  <div class="flex flex-col gap-6">
    <div class="flex items-center gap-4">
      <ElUpload
        :show-file-list="false"
        :before-upload="beforeAvatarUpload"
        :http-request="customUploadAvatar"
        accept="image/*"
      >
        <ElAvatar :size="72" :src="avatarUrl" class="cursor-pointer" />
      </ElUpload>
      <span class="text-foreground/60 text-sm">
        {{ uploading ? '头像上传中...' : '点击头像更换' }}
      </span>
    </div>
    <ProfileBaseSetting
      ref="profileBaseSettingRef"
      :form-schema="formSchema"
      @submit="handleSubmit"
    />
  </div>
</template>
