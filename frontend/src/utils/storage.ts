export type HasAuth = {
  twitter: boolean
}

export const cookies = () =>
  document.cookie.split('; ').reduce(
    (prev, current) => {
      const [name, value] = current.split('=')
      prev[name] = value
      return prev
    },
    {} as Record<string, string>
  )

export const setCookie = (
  name: string,
  value: string,
  expirationMillis: number | null = null
) => {
  let expires = ''
  if (expirationMillis) {
    const date = new Date()
    date.setTime(date.getTime() + expirationMillis)
    expires = `;expires=${date.toUTCString()}`
  }
  document.cookie = `${name}=${value}${expires};path=/`
}

export const clearCookie = (name: string) => {
  document.cookie = `${name}=;expires=${new Date(0).toUTCString()};path=/`
}

export const getJwtToken = (): string | undefined => cookies()['access_token']

export const clearJwtToken = () => clearCookie('access_token')

export const setJwtToken = (token: string) =>
  setCookie('access_token', token, 1000 * 60 * 60 * 24 * 2)

export const hasJwtToken = () => !!getJwtToken()

export const storeObjectInLocalStorage = <A>(key: string, value: A | undefined | null) => {
  if (value === undefined || value === null)
    clearObjectFromLocalStorage(key)
  else
    localStorage.setItem(key, JSON.stringify(value))
}

export const getObjectFromLocalStorage = <A>(key: string): A | null =>
  JSON.parse(localStorage.getItem(key) || 'null')

export const clearObjectFromLocalStorage = (key: string) =>
  localStorage.removeItem(key)

export const setAuthStatus = (hasAuth: HasAuth | undefined | null) =>
  storeObjectInLocalStorage('hasAuth', hasAuth)

export const getAuthStatus = (): HasAuth => {
  const hasAuth = getObjectFromLocalStorage('hasAuth') as HasAuth | null
  return hasAuth || { twitter: false }
}

export const updateAuthStatus = (f: (current: HasAuth) => HasAuth) => {
  const current = getAuthStatus()
  setAuthStatus(f(current))
}
