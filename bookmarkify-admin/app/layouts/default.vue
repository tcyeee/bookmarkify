<template>
  <el-container class="h-screen w-full">
    <el-aside width="220px" class="border-r border-gray-200 bg-white">
      <div class="h-16 flex items-center justify-center border-b border-gray-200">
        <h1 class="text-xl font-bold text-primary-600">
          Bookmarkify
        </h1>
      </div>
      <el-menu router :default-active="$route.path" class="border-r-0">
        <el-menu-item index="/">
          <el-icon>
            <Document />
          </el-icon>
          <span>书签管理</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="bg-white border-b border-gray-200 flex items-center justify-between h-16 px-6">
        <div class="text-lg font-medium text-gray-700">
          后台管理系统
        </div>
        <div class="flex items-center gap-4">
          <span class="text-sm text-gray-500">Admin</span>
          <el-button type="danger" link @click="handleLogout">
            退出登录
          </el-button>
        </div>
      </el-header>
      <el-main class="bg-gray-50 p-6">
        <slot />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
const { logout } = useAuth();
const handleLogout = () => {
  logout();
};
</script>

<style>
body {
  margin: 0;
}
.el-menu {
  background-color: transparent;
}
</style>
