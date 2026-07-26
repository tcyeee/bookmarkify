// composables/useGoogleOAuth.ts
// 站点为纯静态部署（见 CLAUDE.md），无法承接 GIS redirect 模式所需的服务端 POST 回调，
// 因此登录改为整页跳转到 Google 经典 OAuth2 隐式流程（response_type=id_token），
// 凭据随回调地址的 URL hash 带回，全程纯客户端即可完成，无需后端参与跳转。
const STATE_KEY = 'bookmarkify-google-oauth-state'
const NONCE_KEY = 'bookmarkify-google-oauth-nonce'

function decodeJwtPayload(idToken: string): Record<string, any> | undefined {
  try {
    const payload = idToken.split('.')[1] || ''
    const json = decodeURIComponent(
      atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join(''),
    )
    return JSON.parse(json)
  } catch {
    return undefined
  }
}

export function useGoogleOAuth() {
  const config = useRuntimeConfig()
  const googleClientId = (config.public.googleClientId as string | undefined) || ''

  function redirectToGoogleLogin() {
    if (!import.meta.client) return
    if (!googleClientId) return

    const redirectUri = `${location.origin}/auth/google/callback`
    const state = Math.random().toString(36).slice(2) + Date.now().toString(36)
    const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36)
    sessionStorage.setItem(STATE_KEY, state)
    sessionStorage.setItem(NONCE_KEY, nonce)

    const url =
      `https://accounts.google.com/o/oauth2/v2/auth?client_id=${encodeURIComponent(googleClientId)}` +
      `&redirect_uri=${encodeURIComponent(redirectUri)}` +
      `&response_type=id_token` +
      `&scope=${encodeURIComponent('openid email profile')}` +
      `&state=${encodeURIComponent(state)}` +
      `&nonce=${encodeURIComponent(nonce)}` +
      `&prompt=select_account`

    location.href = url
  }

  // 解析回调页 URL hash，校验 state 与 nonce 后取出 idToken；仅回调页调用一次（会清空缓存的校验值）
  function consumeGoogleCallback(hash: string): { idToken: string } | { error: string } {
    const params = new URLSearchParams(hash.replace(/^#/, ''))

    const expectedState = sessionStorage.getItem(STATE_KEY) || ''
    const expectedNonce = sessionStorage.getItem(NONCE_KEY) || ''
    sessionStorage.removeItem(STATE_KEY)
    sessionStorage.removeItem(NONCE_KEY)

    const error = params.get('error')
    if (error) return { error }

    if (!expectedState || params.get('state') !== expectedState) return { error: 'state 校验失败' }

    const idToken = params.get('id_token') || ''
    if (!idToken) return { error: '未获取到 Google 凭据' }

    const payload = decodeJwtPayload(idToken)
    if (!expectedNonce || payload?.nonce !== expectedNonce) return { error: 'nonce 校验失败' }

    return { idToken }
  }

  return { googleClientId, redirectToGoogleLogin, consumeGoogleCallback }
}
