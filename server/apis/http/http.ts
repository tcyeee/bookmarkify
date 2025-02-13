import { Environments } from '~/types/environments';
import { type FetchConfig, type Result } from './types';
import { StoreUser } from "@/stores/user.store";

export default class http {
    static put(path: string, params?: any): any {
        return this.start(path, "PUT", params);
    }

    static get(path: string, params?: any): any {
        return this.start(path, "GET", params);
    }

    static post(path: string, params?: any): any {
        return this.start(path, "POST", params);
    }

    static async start(path: string, method: string, params?: any): Promise<any> {
        console.log(`[DEBUG] 产生${method}请求,路径为${path}`);
        const userStore = StoreUser()
        const config: FetchConfig = {
            headers: { 'Content-Type': 'application/json; charset=utf-8' },
            method,
        }

        // 如果有有效的 token，就添加到请求头
        if (userStore.auth?.token) config.headers['x-access-token'] = userStore.auth.token;

        // 参数处理
        if (params) {
            // 如果不是GET就格式化参数为JSON
            if (method !== "GET") {
                config.body = JSON.stringify(params)
            }
            else {
                // 将参数拼接到URL后面
                const queryString = Object.keys(params)
                    .map(key => `${encodeURIComponent(key)}=${encodeURIComponent(params[key])}`)
                    .join('&');
                path += `?${queryString}`;
            }
        }

        try {
            const env = useRuntimeConfig()
            const response = await fetch(env.public.apiBase + path, config);
            const data = await response.json();
            return resultCheck(data as Result<object>);
        } catch (error) {
            return Promise.reject(error);
        }
    }
}

// 对返回结果进行检查
function resultCheck(result: Result<object>): Promise<any> {
    if (result.ok) return Promise.resolve(result.data);
    // @ts-ignore
    ElMessage.error(`Oops, ${result.msg}`)
    return Promise.reject(result);
}
