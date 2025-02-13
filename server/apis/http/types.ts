export interface FetchConfig {
    body?: any
    method: string
    headers: any
}

export interface Result<T> {
    code: number
    level: string,
    msg: string

    data: T
    ok: boolean
    dataType?: DateType
}

export enum DateType {
    NORMAL = 1,  // 普通数据
    ENCRYPT = 10 // 加密
}

