<template>
  <div>
    <div class="setting-title mt-25">账户绑定</div>
    <div class="setting-subtitle">手机号</div>
    <label class="cy-input cy-validator">
      <span class="icon--phone" />
      <input type="tel" class="tabular-nums" required placeholder="phone" pattern="[0-9]*" minlength="10" maxlength="10" title="Must be 10 digits" />
    </label>
    <p class="cy-validator-hint">Must be 10 digits</p>

    <div class="setting-subtitle">邮箱</div>
    <label class="cy-input cy-validator">
      <span class="icon--mail" />
      <input type="email" placeholder="mail@site.com" required />
    </label>
    <div class="cy-validator-hint hidden">Enter valid email address</div>
  </div>
</template>

<script lang="ts" setup>
</script>

<style>
</style>