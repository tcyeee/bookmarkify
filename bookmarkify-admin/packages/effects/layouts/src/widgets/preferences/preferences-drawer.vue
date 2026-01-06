<script setup lang="ts">
import type {
  BreadcrumbStyleType,
  BuiltinThemeType,
  ContentCompactType,
  LayoutHeaderMenuAlignType,
  LayoutHeaderModeType,
  LayoutType,
  NavigationStyleType,
  PreferencesButtonPositionType,
  ThemeModeType,
} from "@vben/types";

import type { SegmentedItem } from "@vben-core/shadcn-ui";

import { computed, ref } from "vue";

import { Copy, Pin, PinOff, RotateCw } from "@vben/icons";
import {
  clearPreferencesCache,
  preferences,
  resetPreferences,
  usePreferences,
} from "@vben/preferences";

import { useVbenDrawer } from "@vben-core/popup-ui";
import {
  VbenButton,
  VbenIconButton,
  VbenSegmented,
} from "@vben-core/shadcn-ui";
import { globalShareState } from "@vben-core/shared/global-state";

import { useClipboard } from "@vueuse/core";

import {
  Animation,
  Block,
  Breadcrumb,
  BuiltinTheme,
  ColorMode,
  Content,
  Copyright,
  FontSize,
  Footer,
  General,
  GlobalShortcutKeys,
  Header,
  Layout,
  Navigation,
  Radius,
  Sidebar,
  Tabbar,
  Theme,
  Widget,
} from "./blocks";

const emit = defineEmits<{ clearPreferencesAndLogout: [] }>();

const message = globalShareState.getMessage();

const appDynamicTitle = defineModel<boolean>("appDynamicTitle");
const appLayout = defineModel<LayoutType>("appLayout");
const appColorGrayMode = defineModel<boolean>("appColorGrayMode");
const appColorWeakMode = defineModel<boolean>("appColorWeakMode");
const appContentCompact = defineModel<ContentCompactType>("appContentCompact");
const appWatermark = defineModel<boolean>("appWatermark");
const appWatermarkContent = defineModel<string>("appWatermarkContent");
const appEnableCheckUpdates = defineModel<boolean>("appEnableCheckUpdates");
const appEnableStickyPreferencesNavigationBar = defineModel<boolean>(
  "appEnableStickyPreferencesNavigationBar"
);
const appPreferencesButtonPosition = defineModel<PreferencesButtonPositionType>(
  "appPreferencesButtonPosition"
);

const transitionProgress = defineModel<boolean>("transitionProgress");
const transitionName = defineModel<string>("transitionName");
const transitionLoading = defineModel<boolean>("transitionLoading");
const transitionEnable = defineModel<boolean>("transitionEnable");

const themeColorPrimary = defineModel<string>("themeColorPrimary");
const themeBuiltinType = defineModel<BuiltinThemeType>("themeBuiltinType");
const themeMode = defineModel<ThemeModeType>("themeMode");
const themeRadius = defineModel<string>("themeRadius");
const themeFontSize = defineModel<number>("themeFontSize");
const themeSemiDarkSidebar = defineModel<boolean>("themeSemiDarkSidebar");
const themeSemiDarkHeader = defineModel<boolean>("themeSemiDarkHeader");

const sidebarEnable = defineModel<boolean>("sidebarEnable");
const sidebarWidth = defineModel<number>("sidebarWidth");
const sidebarCollapsed = defineModel<boolean>("sidebarCollapsed");
const sidebarCollapsedShowTitle = defineModel<boolean>(
  "sidebarCollapsedShowTitle"
);
const sidebarAutoActivateChild = defineModel<boolean>(
  "sidebarAutoActivateChild"
);
const sidebarExpandOnHover = defineModel<boolean>("sidebarExpandOnHover");
const sidebarCollapsedButton = defineModel<boolean>("sidebarCollapsedButton");
const sidebarFixedButton = defineModel<boolean>("sidebarFixedButton");
const headerEnable = defineModel<boolean>("headerEnable");
const headerMode = defineModel<LayoutHeaderModeType>("headerMode");
const headerMenuAlign =
  defineModel<LayoutHeaderMenuAlignType>("headerMenuAlign");

const breadcrumbEnable = defineModel<boolean>("breadcrumbEnable");
const breadcrumbShowIcon = defineModel<boolean>("breadcrumbShowIcon");
const breadcrumbShowHome = defineModel<boolean>("breadcrumbShowHome");
const breadcrumbStyleType = defineModel<BreadcrumbStyleType>(
  "breadcrumbStyleType"
);
const breadcrumbHideOnlyOne = defineModel<boolean>("breadcrumbHideOnlyOne");

const tabbarEnable = defineModel<boolean>("tabbarEnable");
const tabbarShowIcon = defineModel<boolean>("tabbarShowIcon");
const tabbarShowMore = defineModel<boolean>("tabbarShowMore");
const tabbarShowMaximize = defineModel<boolean>("tabbarShowMaximize");
const tabbarPersist = defineModel<boolean>("tabbarPersist");
const tabbarDraggable = defineModel<boolean>("tabbarDraggable");
const tabbarWheelable = defineModel<boolean>("tabbarWheelable");
const tabbarStyleType = defineModel<string>("tabbarStyleType");
const tabbarMaxCount = defineModel<number>("tabbarMaxCount");
const tabbarMiddleClickToClose = defineModel<boolean>(
  "tabbarMiddleClickToClose"
);

const navigationStyleType = defineModel<NavigationStyleType>(
  "navigationStyleType"
);
const navigationSplit = defineModel<boolean>("navigationSplit");
const navigationAccordion = defineModel<boolean>("navigationAccordion");

// const logoVisible = defineModel<boolean>('logoVisible');

const footerEnable = defineModel<boolean>("footerEnable");
const footerFixed = defineModel<boolean>("footerFixed");

const copyrightSettingShow = defineModel<boolean>("copyrightSettingShow");
const copyrightEnable = defineModel<boolean>("copyrightEnable");
const copyrightCompanyName = defineModel<string>("copyrightCompanyName");
const copyrightCompanySiteLink = defineModel<string>(
  "copyrightCompanySiteLink"
);
const copyrightDate = defineModel<string>("copyrightDate");
const copyrightIcp = defineModel<string>("copyrightIcp");
const copyrightIcpLink = defineModel<string>("copyrightIcpLink");

const shortcutKeysEnable = defineModel<boolean>("shortcutKeysEnable");
const shortcutKeysGlobalSearch = defineModel<boolean>(
  "shortcutKeysGlobalSearch"
);
const shortcutKeysGlobalLogout = defineModel<boolean>(
  "shortcutKeysGlobalLogout"
);

const shortcutKeysGlobalLockScreen = defineModel<boolean>(
  "shortcutKeysGlobalLockScreen"
);

const widgetGlobalSearch = defineModel<boolean>("widgetGlobalSearch");
const widgetFullscreen = defineModel<boolean>("widgetFullscreen");
const widgetLanguageToggle = defineModel<boolean>("widgetLanguageToggle");
const widgetNotification = defineModel<boolean>("widgetNotification");
const widgetThemeToggle = defineModel<boolean>("widgetThemeToggle");
const widgetSidebarToggle = defineModel<boolean>("widgetSidebarToggle");
const widgetLockScreen = defineModel<boolean>("widgetLockScreen");
const widgetRefresh = defineModel<boolean>("widgetRefresh");

const {
  diffPreference,
  isDark,
  isFullContent,
  isHeaderNav,
  isHeaderSidebarNav,
  isMixedNav,
  isSideMixedNav,
  isSideMode,
  isSideNav,
} = usePreferences();
const { copy } = useClipboard({ legacy: true });

const [Drawer] = useVbenDrawer();

const activeTab = ref("appearance");

const tabs = computed((): SegmentedItem[] => {
  return [
    {
      label: "外观",
      value: "appearance",
    },
    {
      label: "布局",
      value: "layout",
    },
    {
      label: "快捷键",
      value: "shortcutKey",
    },
    {
      label: "通用",
      value: "general",
    },
  ];
});

const showBreadcrumbConfig = computed(() => {
  return (
    !isFullContent.value &&
    !isMixedNav.value &&
    !isHeaderNav.value &&
    preferences.header.enable
  );
});

async function handleCopy() {
  await copy(JSON.stringify(diffPreference.value, null, 2));

  message.copyPreferencesSuccess?.(
    "复制成功",
    "复制成功，请在 app 下的 `src/preferences.ts`内进行覆盖"
  );
}

async function handleClearCache() {
  resetPreferences();
  clearPreferencesCache();
  emit("clearPreferencesAndLogout");
}

async function handleReset() {
  if (!diffPreference.value) {
    return;
  }
  resetPreferences();
  // await loadLocaleMessages(preferences.app.locale);
}
</script>

<template>
  <div>
    <Drawer :description="'自定义偏好设置 & 实时预览'" :title="'偏好设置'" class="!border-0 sm:max-w-sm">
      <template #extra>
        <div class="flex items-center">
          <VbenIconButton :disabled="!diffPreference" :tooltip="'数据有变化，点击可进行重置'" class="relative" @click="handleReset">
            <span v-if="diffPreference" class="bg-primary absolute right-0.5 top-0.5 h-2 w-2 rounded"></span>
            <RotateCw class="size-4" />
          </VbenIconButton>
          <VbenIconButton :tooltip="
              appEnableStickyPreferencesNavigationBar
                ? '关闭首选项导航栏吸顶效果'
                : '开启首选项导航栏吸顶效果'
            " class="relative" @click="
              () =>
                (appEnableStickyPreferencesNavigationBar =
                  !appEnableStickyPreferencesNavigationBar)
            ">
            <PinOff v-if="appEnableStickyPreferencesNavigationBar" class="size-4" />
            <Pin v-else class="size-4" />
          </VbenIconButton>
        </div>
      </template>

      <div>
        <VbenSegmented v-model="activeTab" :tabs="tabs" :class="{
            'sticky-tabs-header': appEnableStickyPreferencesNavigationBar,
          }">
          <template #general>
            <Block :title="'通用'">
              <General v-model:app-dynamic-title="appDynamicTitle" v-model:app-enable-check-updates="appEnableCheckUpdates" v-model:app-watermark="appWatermark" v-model:app-watermark-content="appWatermarkContent" />
            </Block>

            <Block :title="'动画'">
              <Animation v-model:transition-enable="transitionEnable" v-model:transition-loading="transitionLoading" v-model:transition-name="transitionName" v-model:transition-progress="transitionProgress" />
            </Block>
          </template>
          <template #appearance>
            <Block :title="'主题'">
              <Theme v-model="themeMode" v-model:theme-semi-dark-header="themeSemiDarkHeader" v-model:theme-semi-dark-sidebar="themeSemiDarkSidebar" />
            </Block>
            <Block :title="'内置主题'">
              <BuiltinTheme v-model="themeBuiltinType" v-model:theme-color-primary="themeColorPrimary" :is-dark="isDark" />
            </Block>
            <Block :title="'圆角'">
              <Radius v-model="themeRadius" />
            </Block>
            <Block :title="'字体大小'">
              <FontSize v-model="themeFontSize" />
            </Block>
            <Block :title="'其它'">
              <ColorMode v-model:app-color-gray-mode="appColorGrayMode" v-model:app-color-weak-mode="appColorWeakMode" />
            </Block>
          </template>
          <template #layout>
            <Block :title="'布局'">
              <Layout v-model="appLayout" />
            </Block>
            <Block :title="'内容'">
              <Content v-model="appContentCompact" />
            </Block>

            <Block :title="'侧边栏'">
              <Sidebar v-model:sidebar-auto-activate-child="sidebarAutoActivateChild" v-model:sidebar-collapsed="sidebarCollapsed" v-model:sidebar-collapsed-show-title="sidebarCollapsedShowTitle" v-model:sidebar-enable="sidebarEnable" v-model:sidebar-expand-on-hover="sidebarExpandOnHover" v-model:sidebar-width="sidebarWidth" v-model:sidebar-collapsed-button="sidebarCollapsedButton" v-model:sidebar-fixed-button="sidebarFixedButton" :current-layout="appLayout" :disabled="!isSideMode" />
            </Block>

            <Block :title="'顶栏'">
              <Header v-model:header-enable="headerEnable" v-model:header-menu-align="headerMenuAlign" v-model:header-mode="headerMode" :disabled="isFullContent" />
            </Block>

            <Block :title="'导航菜单'">
              <Navigation v-model:navigation-accordion="navigationAccordion" v-model:navigation-split="navigationSplit" v-model:navigation-style-type="navigationStyleType" :disabled="isFullContent" :disabled-navigation-split="!isMixedNav" />
            </Block>

            <Block :title="'面包屑导航'">
              <Breadcrumb v-model:breadcrumb-enable="breadcrumbEnable" v-model:breadcrumb-hide-only-one="breadcrumbHideOnlyOne" v-model:breadcrumb-show-home="breadcrumbShowHome" v-model:breadcrumb-show-icon="breadcrumbShowIcon" v-model:breadcrumb-style-type="breadcrumbStyleType" :disabled="
                  !showBreadcrumbConfig ||
                  !(isSideNav || isSideMixedNav || isHeaderSidebarNav)
                " />
            </Block>
            <Block :title="'标签栏'">
              <Tabbar v-model:tabbar-draggable="tabbarDraggable" v-model:tabbar-enable="tabbarEnable" v-model:tabbar-persist="tabbarPersist" v-model:tabbar-show-icon="tabbarShowIcon" v-model:tabbar-show-maximize="tabbarShowMaximize" v-model:tabbar-show-more="tabbarShowMore" v-model:tabbar-style-type="tabbarStyleType" v-model:tabbar-wheelable="tabbarWheelable" v-model:tabbar-max-count="tabbarMaxCount" v-model:tabbar-middle-click-to-close="tabbarMiddleClickToClose" />
            </Block>
            <Block :title="'小部件'">
              <Widget v-model:app-preferences-button-position="
                  appPreferencesButtonPosition
                " v-model:widget-fullscreen="widgetFullscreen" v-model:widget-global-search="widgetGlobalSearch" v-model:widget-language-toggle="widgetLanguageToggle" v-model:widget-lock-screen="widgetLockScreen" v-model:widget-notification="widgetNotification" v-model:widget-refresh="widgetRefresh" v-model:widget-sidebar-toggle="widgetSidebarToggle" v-model:widget-theme-toggle="widgetThemeToggle" />
            </Block>
            <Block :title="'底栏'">
              <Footer v-model:footer-enable="footerEnable" v-model:footer-fixed="footerFixed" />
            </Block>
            <Block v-if="copyrightSettingShow" :title="'版权'">
              <Copyright v-model:copyright-company-name="copyrightCompanyName" v-model:copyright-company-site-link="copyrightCompanySiteLink" v-model:copyright-date="copyrightDate" v-model:copyright-enable="copyrightEnable" v-model:copyright-icp="copyrightIcp" v-model:copyright-icp-link="copyrightIcpLink" :disabled="!footerEnable" />
            </Block>
          </template>

          <template #shortcutKey>
            <Block :title="'全局'">
              <GlobalShortcutKeys v-model:shortcut-keys-enable="shortcutKeysEnable" v-model:shortcut-keys-global-search="shortcutKeysGlobalSearch" v-model:shortcut-keys-lock-screen="shortcutKeysGlobalLockScreen" v-model:shortcut-keys-logout="shortcutKeysGlobalLogout" />
            </Block>
          </template>
        </VbenSegmented>
      </div>

      <template #footer>
        <VbenButton :disabled="!diffPreference" class="mx-4 w-full" size="sm" variant="default" @click="handleCopy">
          <Copy class="mr-2 size-3" />
          {{ '复制偏好设置' }}
        </VbenButton>
        <VbenButton :disabled="!diffPreference" class="mr-4 w-full" size="sm" variant="ghost" @click="handleClearCache">
          {{ '清空缓存 & 退出登录' }}
        </VbenButton>
      </template>
    </Drawer>
  </div>
</template>

<style scoped>
:deep(.sticky-tabs-header [role="tablist"]) {
  position: sticky;
  top: -12px;
  z-index: 9999;
}
</style>
