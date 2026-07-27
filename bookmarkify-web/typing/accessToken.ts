export interface AccessTokenCreateParams {
  name: string
}

export interface AccessTokenVO {
  id: string
  name: string
  tokenPrefix: string
  lastUsedAt?: string | null
  createTime: number
}

export interface AccessTokenCreatedVO {
  id: string
  name: string
  token: string
  createTime: number
}
