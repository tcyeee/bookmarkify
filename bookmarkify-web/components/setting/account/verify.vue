<template>
  <div>
    <div class="setting-title mt-25">账户绑定</div>
    <div class="setting-subtitle">手机号</div>
    <label class="cy-input cy-validator">
      <span class="icon--phone icon-size-22 text-gray-500" />
      <input type="tel" class="tabular-nums" required placeholder="phone" pattern="[0-9]*" minlength="11" maxlength="11" />
    </label>
    <p class="text-xs cy-validator-hint">手机号格式错误</p>

    <div class="setting-subtitle">邮箱</div>
    <label class="cy-input cy-validator">
      <span class="icon--mail icon-size-22 text-gray-500" />
      <input type="email" placeholder="mail@site.com" required />
    </label>
    <p class="text-xs cy-validator-hint">邮箱格式错误</p>
  </div>
</template>

<script lang="ts" setup></script>

<style></style>
