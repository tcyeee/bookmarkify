export interface FetchConfig {
    body?: any
    method: string
    headers: any
}

export interface Result<T> {
    msg: string
    code: number
    data: T
    status: boolean
    notice?: NoticeType
}

export enum NoticeType {
    NONE = "NONE",
    SUCCESS = "SUCCESS",
    INFO = "INFO",
    WARN = "WARN",
    ERROR = "ERROR"
}

