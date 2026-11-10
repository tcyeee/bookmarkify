/**
 * 该文件可自行根据业务逻辑进行调整
 */
import type { RequestClientOptions } from '@vben/request';

import { useAppConfig } from '@vben/hooks';
import {
  defaultResponseInterceptor,
  errorMessageResponseInterceptor,
  RequestClient,
} from '@vben/request';
import { useAccessStore } from '@vben/stores';

import { ElMessage } from 'element-plus';

import { useAuthStore } from '#/store';

const { apiURL } = useAppConfig(import.meta.env, import.meta.env.PROD);

function createRequestClient(baseURL: string, options?: RequestClientOptions) {
  const client = new RequestClient({
    ...options,
    baseURL,
  });

  // 请求头处理：鉴权走 Sa-Token 的 satoken 头（与 bookmarkify-web 一致），不是
  // 通用的 Authorization: Bearer——后端从未读取过 Authorization 头做鉴权
  client.addRequestInterceptor({
    fulfilled: async (config) => {
      const accessStore = useAccessStore();

      config.headers.satoken = accessStore.accessToken ?? '';
      config.headers['Accept-Language'] = 'zh-CN';
      return config;
    },
  });

  // 处理返回的响应数据格式
  client.addResponseInterceptor(
    defaultResponseInterceptor({
      codeField: 'code',
      dataField: 'data',
      successCode: 0,
    }),
  );

  // 通用的错误处理,如果没有进入上面的错误处理逻辑，就会进入这里
  client.addResponseInterceptor(
    errorMessageResponseInterceptor((msg: string, error) => {
      // 这里可以根据业务进行定制,你可以拿到 error 内的信息进行定制化处理，根据不同的 code 做不同的提示，而不是直接使用 message.error 提示 msg
      // 后端 ResultWrapper 的错误提示字段是 msg，error/message 是兜底非常规返回格式
      const responseData = error?.response?.data ?? {};
      const errorMessage = responseData?.error ?? responseData?.message ?? responseData?.msg ?? '';
      const errorCode = responseData?.code ?? 0;

      // 后端给每个响应都带了 X-Request-Id（PreRequestFilter 生成，同一串也打在每行文本日志上、
      // 并写进 ai_call_log / scrapper_call_log 等账本的 request_id 列）。把它显示出来，
      // "报错了但查不到是哪次请求"才算有解：拿这一串就能 grep 出那次调用的全部现场。
      // 只在错误提示里带，正常响应不打扰。
      const requestId = error?.response?.headers?.['x-request-id'];
      const withRequestId = (text: string) =>
        requestId ? `${text}（请求号 ${requestId}）` : text;

      if (errorCode === 101) {
        const authStore = useAuthStore();
        // 未登录是会话过期，不是故障，不必给请求号
        ElMessage.error(errorMessage || msg);
        void authStore.logout();
        return;
      }

      if (errorCode >= 100 && errorCode < 200) {
        ElMessage.error(withRequestId(errorMessage || msg));
        return;
      }

      // 如果没有错误信息，则会根据状态码进行提示
      ElMessage.error(withRequestId(errorMessage || msg));
    }),
  );

  return client;
}

export const requestClient = createRequestClient(apiURL, { responseReturn: 'data' });

export const baseRequestClient = new RequestClient({ baseURL: apiURL });
