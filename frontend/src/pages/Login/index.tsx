
import { useState } from 'preact/hooks';
import './style.css';
import { ModalMessage } from '../../components/ModalMessage';
import { useLocation } from 'preact-iso';

export function Login() {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(null as string | null);
  const location = useLocation();
  const redirectTo = location.query.redirect || '/';

  const onSubmit = async (e: Event) => {
    e.preventDefault();
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
          setError("No token received from the server!")
        } else {
          sessionStorage.setItem('jwtToken', body.token)
          location.route(redirectTo);
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
      setError("Exception while logging in, probably a bug, check the console!")
    }
  }

  const onUsernameChange = (e: Event) =>
    setUsername((e.target as HTMLInputElement).value);
  const onPasswordChange = (e: Event) =>
    setPassword((e.target as HTMLInputElement).value);

  return (
    <div class="login" id="login">
      <ModalMessage type="error" isEnabled={!!error} onDisable={() => setError(null)}>
        {error}
      </ModalMessage>

      <section class="section">
        <div class="container">
          <h1 class="title">
            Login
          </h1>
          <form onSubmit={onSubmit} class="box">
            <div class="field">
              <label class="label">Username</label>
              <div class="control">
                <input class="input" type="text" onInput={onUsernameChange} required />
              </div>
            </div>
            <div class="field">
              <label class="label">Password</label>
              <div class="control">
                <input class="input" type="password" onInput={onPasswordChange} required />
              </div>
            </div>
            <input class="button is-primary" type="submit" value="Submit" />
          </form>
        </div>
      </section>
    </div>
  );
}
