export type Locale = 'zh-CN';

export const messages: Record<Locale, Record<string, string>> = {
  'zh-CN': {
    cancel: '取消',
    collapse: '收起',
    confirm: '确认',
    expand: '展开',
    prompt: '提示',
    reset: '重置',
    submit: '提交',
  },
};

export const getMessages = (_locale: Locale) => messages['zh-CN'];
