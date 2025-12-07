import type { UserInfoEntity } from '@api/typing';
import type { Result } from './types';
import { useUserStore } from "@stores/user.store";

export default class http {
    static get(path: string, params?: any): any {
        return this.start(path, "GET", params);
    }

    static post(path: string, params?: any): any {
        return this.start(path, "POST", params);
    }

    static async upload(path: string, file: File): Promise<any> {
        return this.uploadFile(path, file);
    }

    static async uploadFile(path: string, file: File): Promise<any> {
        const userStore = useUserStore()

        // 除了Login，其他都需要token
        if (!path.startsWith("/auth/") && !userStore.account?.token) await userStore.login()

        // 创建 FormData
        const formData = new FormData();
        formData.append('file', file);

        // 创建请求
        console.log(`[API] UPLOAD::${path}`);
        const token = userStore.account?.token ?? ""
        const request: Request = new Request(useRuntimeConfig().public.apiBase + path, {
            headers: { 'satoken': token },
            body: formData,
            method: 'POST',
        });

        try {
            const response = await fetch(request);
            const data = await response.json();
            return resultCheck(data as Result<object>, request);
        } catch (error) {
            console.log("===================");
            console.log(error);
            console.log("================");
            // @ts-ignore
            if (error instanceof TypeError) ElMessage.error(`Oops,网络错误,请重试`)
            return Promise.reject(error);
        }
    }

    static async start(path: string, method: string, params?: any): Promise<any> {
        const userStore = useUserStore()

        // 除了Login，其他都需要token
        if (method != "GET" && !path.startsWith("/auth/") && !userStore.account?.token) userStore.login()

        // 创建请求
        console.log(`[API] ${method}::${path}`);
        if (method == "GET") path += `?${new URLSearchParams(params).toString()}`;
        const body = method == "POST" ? JSON.stringify(params) : undefined;
        const request: Request = new Request(useRuntimeConfig().public.apiBase + path, {
            headers: { 'Content-Type': 'application/json', 'satoken': userStore.account?.token ?? "" },
            body: body,
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

    // 如果遇到token失效,则重新登录
    if ([101].includes(result.code)) {
        const userStore = useUserStore()
        const account: UserInfoEntity = await userStore.login();
        if (account.token) {
            request.headers.set("satoken", account.token)
            return await fetch(request);
        }
    }

    // 如果result.code是“1”开头，则需要提示
    if (result.code.toString().startsWith("1")) ElMessage.error(`Oops, ${result.msg}`)
    return Promise.reject(result);
}