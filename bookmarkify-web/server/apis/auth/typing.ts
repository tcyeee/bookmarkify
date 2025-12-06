export interface LoginParams {
    account: string
    password: string
}

export interface UserAuthParams {
    deviceUid: string
    token?: string
}