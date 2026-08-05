// composables/useGithubOAuth.ts
// 开 GitHub 授权弹窗，等回调页 postMessage 回 code，校验 state 与 origin 后 resolve。
// 调用方（按钮/绑定弹窗）拿到 code 后自行决定走登录还是绑定 API。
//
// 移动端浏览器/微信内置浏览器对 window.open 弹窗支持不稳定(常被拦截或行为异常)，
// 因此额外提供 redirectToGithubLogin：整页跳转，state 存 sessionStorage，
// 回调页 pages/auth/github/callback.vue 检测到没有 window.opener 时按此流程校验并直接完成登录。
const MSG_SOURCE = 'bookmarkify-github-oauth'
const REDIRECT_STATE_KEY = 'bookmarkify-github-oauth-redirect-state'

export function useGithubOAuth() {
  const config = useRuntimeConfig()
  const githubClientId = (config.public.githubClientId as string | undefined) || ''

  function redirectToGithubLogin() {
    if (!import.meta.client) return
    if (!githubClientId) return

    const redirectUri = `${location.origin}/auth/github/callback`
    const state = Math.random().toString(36).slice(2) + Date.now().toString(36)
    sessionStorage.setItem(REDIRECT_STATE_KEY, state)

    const url =
      `https://github.com/login/oauth/authorize?client_id=${encodeURIComponent(githubClientId)}` +
      `&redirect_uri=${encodeURIComponent(redirectUri)}` +
      `&scope=${encodeURIComponent('read:user user:email')}` +
      `&state=${encodeURIComponent(state)}`

    location.href = url
  }

  function requestGithubCode(): Promise<{ code: string; redirectUri: string }> {
    return new Promise((resolve, reject) => {
      if (!import.meta.client) return reject(new Error('仅客户端可用'))
      if (!githubClientId) return reject(new Error('未配置 GitHub ClientId'))

      const redirectUri = `${location.origin}/auth/github/callback`
      const state = Math.random().toString(36).slice(2) + Date.now().toString(36)
      const url =
        `https://github.com/login/oauth/authorize?client_id=${encodeURIComponent(githubClientId)}` +
        `&redirect_uri=${encodeURIComponent(redirectUri)}` +
        `&scope=${encodeURIComponent('read:user user:email')}` +
        `&state=${encodeURIComponent(state)}`

      const popup = window.open(url, 'github-oauth', 'width=600,height=720')
      if (!popup) return reject(new Error('弹窗被拦截，请允许弹出窗口'))

      let settled = false
      const cleanup = () => {
        settled = true
        window.removeEventListener('message', onMessage)
        clearInterval(timer)
      }

      function onMessage(e: MessageEvent) {
        if (e.origin !== location.origin) return
        const d = e.data
        if (!d || d.source !== MSG_SOURCE) return
        cleanup()
        if (d.error) return reject(new Error(d.error))
        if (d.state !== state) return reject(new Error('state 校验失败'))
        if (!d.code) return reject(new Error('未获取到授权码'))
        resolve({ code: d.code, redirectUri })
      }
      window.addEventListener('message', onMessage)

      // 用户手动关闭弹窗 => 视为取消
      const timer = setInterval(() => {
        if (settled) return
        if (popup.closed) {
          cleanup()
          reject(new Error('已取消 GitHub 授权'))
        }
      }, 500)
    })
  }

  return { githubClientId, requestGithubCode, redirectToGithubLogin }
}

export const GITHUB_OAUTH_REDIRECT_STATE_KEY = REDIRECT_STATE_KEY
