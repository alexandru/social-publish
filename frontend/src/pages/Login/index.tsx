import { useState } from 'preact/hooks'
import './style.css'
import { ModalMessage } from '../../components/ModalMessage'
import { useLocation } from 'preact-iso'
import { setAuthStatus, setJwtToken, storeObjectInLocalStorage } from '../../utils/storage'

export function Login() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState(null as string | null)
  const location = useLocation()
  const redirectTo = location.query.redirect || '/form'

  if (location.query.error)
    switch (location.query.error) {
      case '401':
        setError('Unauthorized! Please log in...')
        break
      default:
        setError('Forbidden! Please log in...')
        break
    }

  const hideError = () => {
    if (location.query.error) {
      let newPath = location.path
      for (const key of Object.keys(location.query)) {
        if (key === 'error') continue
        newPath += newPath.includes('?')
          ? '&'
          : '?' + key + '=' + encodeURIComponent(location.query[key])
      }
      location.route(newPath)
    }
    setError(null)
  }

  const onSubmit = async (e: Event) => {
    e.preventDefault()
    try {
      const response = await fetch('/api/login', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ username, password })
      })
      const body = await response.json()
      if (response.status === 200) {
        if (!body.token) {
          setError('No token received from the server!')
        } else {
          setJwtToken(body.token)
          setAuthStatus(body.hasAuth)
          location.route(redirectTo)
        }
      } else {
        if (body?.error) {
          setError(`${body.error}!`)
        } else {
          console.warn(`Error while logging in:`, response.status, body)
          setError(`HTTP ${response.status} error while logging in! `)
        }
      }
    } catch (e) {
      console.error(`While logging in`, e)
      setError('Exception while logging in, probably a bug, check the console!')
    }
  }

  const onUsernameChange = (e: Event) => setUsername((e.target as HTMLInputElement).value)
  const onPasswordChange = (e: Event) => setPassword((e.target as HTMLInputElement).value)

  return (
    <div className="login" id="login">
      <ModalMessage type="error" isEnabled={!!error} onDisable={hideError}>
        {error}
      </ModalMessage>

      <section className="section">
        <div className="container">
          <h1 className="title">Login</h1>
          <form onSubmit={onSubmit} className="box">
            <div className="field">
              <label className="label">Username</label>
              <div className="control">
                <input className="input" type="text" onInput={onUsernameChange} required />
              </div>
            </div>
            <div className="field">
              <label className="label">Password</label>
              <div className="control">
                <input className="input" type="password" onInput={onPasswordChange} required />
              </div>
            </div>
            <input className="button is-primary" type="submit" value="Submit" />
          </form>
        </div>
      </section>
    </div>
  )
}
