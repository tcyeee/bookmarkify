import { type FetchConfig, type Result } from './types';
import { StoreUser } from "@/stores/user.store";
import { limitAction } from "~/server/utils/BaseUtils";


export default class http {
    static get(path: string, params?: any): any {
        return this.start(path, "GET", params);
    }

    static post(path: string, params?: any): any {
        return this.start(path, "POST", params);
    }

    static async start(path: string, method: string, params?: any): Promise<any> {
        const userStore = StoreUser()
        console.log(`[DEBUG] ${method}::${path}`);

        // 只要不是校验接口,都需要提前去登陆
        const isAuthApi = path.startsWith('/auth/');
        const hasToken = !!userStore.auth?.token
        if (!isAuthApi && !hasToken) await userStore.loginByDeviceUid();


        // 创建请求
        const config: FetchConfig = {
            headers: { 'Content-Type': 'application/json; charset=utf-8' },
            method,
        }
        if (hasToken) config.headers['satoken'] = userStore.auth.token;

        // 格式化参数
        if (params) {
            if (method !== "GET") config.body = JSON.stringify(params)
            else {
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
            // @ts-ignore
            if (error instanceof TypeError) ElMessage.error(`Oops,网络错误,请重试`)
            return Promise.reject(error);
        }
    }
}

// 对返回结果进行检查
function resultCheck(result: Result<object>): Promise<any> {
    if (result.ok) return Promise.resolve(result.data);

    // 重新登陆
    if ([101, 107].includes(result.code)) {
        StoreUser().logout()
        limitAction(3, restart)
        return Promise.reject(result);
    }

    // @ts-ignore
    ElMessage.error(`Oops, ${result.msg}`)
    return Promise.reject(result);
}

// 累计单位时间内系统的重启次数,超出限制则触发报警
function restart() {
    // @ts-ignore
    setTimeout(() => window.location.reload(), 500);
}
