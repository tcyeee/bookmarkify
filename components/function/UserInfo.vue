<template>
  <div>
    <div onclick="dialog_userInfo.showModal()">
      <svg width="20" height="20" viewBox="0 0 48 48" fill="none" xmlns="http://www.w3.org/2000/svg">
        <circle cx="24" cy="12" r="8" fill="none" stroke="#333" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M42 44C42 34.0589 33.9411 26 24 26C14.0589 26 6 34.0589 6 44" stroke="#333" stroke-width="4" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </div>

    <dialog id="dialog_userInfo" class="cy-modal">
      <div class="cy-modal-box w-11/12 max-w-5xl">
        <h3 class="font-bold text-lg">用户信息</h3>
        <p class="py-4">Click the button below to close</p>

        <div class="cy-modal-action">
          <AuthLoginOutButton />
          <form method="dialog">
            <button class="cy-btn">
              <kbd class="cy-kbd">Esc</kbd>
              关闭
            </button>
          </form>
        </div>
      </div>
    </dialog>
  </div>
</template>

<script lang="ts" setup>
</script>

<style>
</style>