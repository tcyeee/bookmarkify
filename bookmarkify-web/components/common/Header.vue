<template>
  <div
    class="px-4 h-16 bg-white w-full overflow-hidden shadow flex items-center select-none cursor-pointer"
    @click="navigateTo('/')">
    <div class="flex items-center gap-2">
      <Icon icon="memory:device" class="size-[30px] hover:text-white" />
      <div class="font-jersey10 font-bold text-4xl">bookmarkify</div>
    </div>
  </div>
</template>
