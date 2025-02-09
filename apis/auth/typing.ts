export interface LoginParams {
    account: string
    password: string
}

export interface UserStore {
    token: string
    nickName?: string
    mail?: string
}