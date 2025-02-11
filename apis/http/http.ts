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
            method,
            headers: {
                'Content-Type': 'application/json; charset=utf-8',
                'Authorization': null,
            },
        }

        // 如果有TOKEN就加上
        if (userStore.profile?.token != undefined) {
            config.headers.Authorization = `Bearer ${userStore.profile.token}`
        }

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
            const response = await fetch("/api" + path, config);
            if (!response.ok) {
                ElMessage.error(`Oops, ${response.status} (${response.statusText})`)
            }
            const data = await response.json();
            return resultCheck(data as Result<object>);
        } catch (error) {
            return Promise.reject(error);
        }
    }
}


enum NoticeType {
    INFO = 'INFO',
    WARN = 'WARN',
    ERROR = 'ERROR',
    SUCCESS = 'SUCCESS',
    NONE = 'NONE'
}

// 对返回结果进行检查
function resultCheck(result: Result<object>): Promise<any> {
    // 系统正常
    if (result.status) return Promise.resolve(result.data);

    switch (result.notice) {
        case NoticeType.NONE:
        case NoticeType.INFO:
        case NoticeType.SUCCESS:
        case NoticeType.WARN: console.log("WARN"); break;
        case NoticeType.ERROR: ElMessage.error(`Oops, ${result.msg}`); break;
    }
    return Promise.reject(result);
}
