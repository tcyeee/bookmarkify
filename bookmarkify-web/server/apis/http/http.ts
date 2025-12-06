import type { Result } from './types';
import { useUserStore } from "@stores/user.store";
import type { UserEntity } from '../auth';

export default class http {
    static get(path: string, params?: any): any {
        return this.start(path, "GET", params);
    }

    static post(path: string, params?: any): any {
        return this.start(path, "POST", params);
    }

    static async start(path: string, method: string, params?: any): Promise<any> {
        const userStore = useUserStore()

        // 除了Login，其他都需要token
        if (method != "GET" && !path.startsWith("/auth/") && !userStore.account?.token) userStore.login()

        // 创建请求
        console.log(`[DEBUG] ${method}::${path}`);
        if (method == "GET") path += `?${new URLSearchParams(params).toString()}`;
        const request: Request = new Request(useRuntimeConfig().public.apiBase + path, {
            headers: { 'Content-Type': 'application/json', 'satoken': userStore.account?.token ?? "" },
            body: JSON.stringify(params),
            method: method,
        });

        try {
            const response = await fetch(request);
            const data = await response.json();
            return resultCheck(data as Result<object>, request);
        } catch (error) {
            // @ts-ignore
            if (error instanceof TypeError) ElMessage.error(`Oops,网络错误,请重试`)
            return Promise.reject(error);
        }
    }
}

// 对返回结果进行检查
async function resultCheck(result: Result<object>, request: Request): Promise<any> {
    if (result.ok) return Promise.resolve(result.data);

    // // 如果遇到token失效,则重新登录
    // if ([101, 107].includes(result.code)) {
    //     const userStore = useUserStore()
    //     const userAuth: UserEntity = await userStore.login();
    //     // todo 添加token后重新请求
    //     if (userAuth.token) {
    //         request.headers.set("satoken", userAuth.token)
    //         return await fetch(request);
    //     }
    // }

    // @ts-ignore
    ElMessage.error(`Oops, ${result.msg}`)
    return Promise.reject(result);
}