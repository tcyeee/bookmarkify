<template>
  <div class="flex items-center justify-center h-screen bg-gray-100">
    <el-card class="w-[400px] shadow-lg">
      <template #header>
        <div class="text-center font-bold text-xl py-2">
          系统登录
        </div>
      </template>
      <el-form
        :model="form"
        size="large"
        @submit.prevent="handleLogin"
      >
        <el-form-item>
          <el-input
            v-model="form.username"
            placeholder="账号 (admin)"
            prefix-icon="User"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码 (admin)"
            show-password
            prefix-icon="Lock"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            class="w-full"
            :loading="loading"
            @click="handleLogin"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>
      <div class="text-center text-gray-400 text-sm mt-4">
        默认账号密码均为 admin
      </div>
    </el-card>
  </div>
</template>

<script setup>
definePageMeta({
  layout: false
})

const form = reactive({
  username: '',
  password: ''
})
const loading = ref(false)
const { login } = useAuth()

const handleLogin = async () => {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }

  loading.value = true
  try {
    await login(form)
    ElMessage.success('登录成功')
    navigateTo('/')
  } catch (e) {
    ElMessage.error(e.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>
