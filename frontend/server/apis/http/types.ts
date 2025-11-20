export interface FetchConfig {
    body?: any
    method: string
    headers: any
}

export interface Result<T> {
    code: number
    msg: string
    data: T
    ok: boolean
}