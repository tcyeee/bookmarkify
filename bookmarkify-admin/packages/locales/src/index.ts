import type { App } from 'vue';
import { ref } from 'vue';

// Hardcoded $t function
const $t = (key: string, _values?: any[] | Record<string, any>): string => {
  return key;
};

const $te = (_key: string) => {
  return false;
};

// Mock i18n object
const i18n = {
  global: {
    t: $t,
    te: $te,
    locale: ref('zh-CN'),
    fallbackLocale: ref('zh-CN'),
    setLocaleMessage: () => { },
  },
  mode: 'legacy'
};

// Mock functions
const loadLocaleMessages = async () => { };
const loadLocalesMap = () => ({});
const loadLocalesMapFromDir = () => ({});
const setupI18n = async (app: App, options: any = {}) => {
  // Manually provide global properties since we are not using vue-i18n plugin
  app.config.globalProperties.$t = $t as any;
  app.config.globalProperties.$te = $te as any;
};

// Mock useI18n
const useI18n = () => ({
  t: $t,
  te: $te,
  locale: ref('zh-CN')
});

export {
  $t,
  $te,
  i18n,
  loadLocaleMessages,
  loadLocalesMap,
  loadLocalesMapFromDir,
  setupI18n,
  useI18n,
};

export {
  type ImportLocaleFn,
  type LocaleSetupOptions,
  type SupportedLanguagesType,
} from './typing';

export type Locale = string;
