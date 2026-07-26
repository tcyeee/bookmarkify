<script lang="ts" setup>
import type { ThemeModeType } from '@vben/types';

import { MoonStar, Sun, SunMoon } from '@vben/icons';
import {
  preferences,
  updatePreferences,
  usePreferences,
} from '@vben/preferences';

import {
  ToggleGroup,
  ToggleGroupItem,
  VbenTooltip,
} from '@vben-core/shadcn-ui';

import ThemeButton from './theme-button.vue';

defineOptions({
  name: 'ThemeToggle',
});

withDefaults(defineProps<{ shouldOnHover?: boolean }>(), {
  shouldOnHover: false,
});

function handleChange(isDark: boolean | undefined) {
  updatePreferences({
    theme: { mode: isDark ? 'dark' : 'light' },
  });
}

const { isDark } = usePreferences();

const PRESETS = [
  {
    icon: Sun,
    name: 'light',
    title: '浅色',
  },
  {
    icon: MoonStar,
    name: 'dark',
    title: '深色',
  },
  {
    icon: SunMoon,
    name: 'auto',
    title: '跟随系统',
  },
];
</script>
<template>
  <div>
    <VbenTooltip :disabled="!shouldOnHover" side="bottom">
      <template #trigger>
        <ThemeButton
          :model-value="isDark"
          type="icon"
          @update:model-value="handleChange"
        />
      </template>
      <ToggleGroup
        :model-value="preferences.theme.mode"
        class="gap-2"
        type="single"
        variant="outline"
        @update:model-value="
          (val) => updatePreferences({ theme: { mode: val as ThemeModeType } })
        "
      >
        <ToggleGroupItem
          v-for="item in PRESETS"
          :key="item.name"
          :value="item.name"
        >
          <component :is="item.icon" class="size-5" />
        </ToggleGroupItem>
      </ToggleGroup>
    </VbenTooltip>
  </div>
</template>
