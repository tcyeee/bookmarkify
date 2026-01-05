export const useAuth = () => {
  const token = useCookie('auth_token')
  const user = useState<{ username: string } | null>('auth_user', () => null)

  const login = async (form: { username: string; password: string }) => {
    // Mock API delay
    await new Promise(resolve => setTimeout(resolve, 500))

    if (form.username === 'admin' && form.password === 'admin') {
      token.value = 'mock-token-12345'
      user.value = { username: 'admin' }
      return true
    }
    throw new Error('Invalid credentials')
  }

  const logout = () => {
    token.value = null
    user.value = null
    navigateTo('/login')
  }

  return { token, user, login, logout }
}
