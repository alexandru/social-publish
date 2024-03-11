import { useLocation } from 'preact-iso'
import { useState } from 'preact/hooks'
import { logOut, logIn, homeOutline, play, logoGithub } from 'ionicons/icons'
import { clearJwtToken, hasJwtToken } from '../utils/storage'

export function NavBar() {
  const [navbarIsActive, setNavbarIsActive] = useState('' as 'is-active' | '')

  const { url } = useLocation()
  const classOfLink = (mainClass: string, path: string) =>
    url == path ? `${mainClass} is-active` : mainClass

  const isLoggedIn = hasJwtToken()

  return (
    <nav className="navbar is-primary" role="navigation" aria-label="main navigation">
      <div className="navbar-brand">
        <a
          role="button"
          className={'navbar-burger ' + navbarIsActive}
          onClick={() => setNavbarIsActive(navbarIsActive == 'is-active' ? '' : 'is-active')}
          aria-label="menu"
          aria-expanded="false"
          data-target="navbarBasicExample"
        >
          <span aria-hidden="true"></span>
          <span aria-hidden="true"></span>
          <span aria-hidden="true"></span>
        </a>
      </div>

      <div id="navbarBasicExample" className={'navbar-menu ' + navbarIsActive}>
        <div className="navbar-start">
          <a className={classOfLink('navbar-item', '/')} href="/">
            <span className="icon">
              <img src={homeOutline} alt="Home" />
            </span>
            <strong>Home</strong>
          </a>
          <IfLoggedIn isLoggedIn={isLoggedIn}>
            <a className={classOfLink('navbar-item', '/form')} href="/form">
              <span className="icon">
                <img src={play} alt="Publish" />
              </span>
              <strong>Publish</strong>
            </a>
          </IfLoggedIn>
          <a className="navbar-item" href="https://github.com/alexandru/social-publish" target="_blank" rel="noreferrer">
            <span className="icon">
              <img src={logoGithub} alt="Help" />
            </span>
            <strong>Help</strong>
          </a>
        </div>

        <div className="navbar-end">
          <div className="navbar-item">
            <div className="buttons">
              {isLoggedIn ? (
                <a className={classOfLink('button is-primary', '/account')} href="/account">
                  <strong>Account</strong>
                </a>
              ) : null}
              <LoginOrLogoutButton isLoggedIn={isLoggedIn} />
            </div>
          </div>
        </div>
      </div>
    </nav>
  )
}

function IfLoggedIn(props: { isLoggedIn: boolean; children: any }) {
  return props.isLoggedIn ? props.children : null
}

function LoginOrLogoutButton(props: { isLoggedIn: boolean }) {
  const location = useLocation()
  const onLogout = () => {
    clearJwtToken()
    window.location.href = '/'
    return
  }

  if (props.isLoggedIn) {
    return (
      <a className="button is-primary" onClick={onLogout}>
        <strong>Logout</strong>
        <span className="icon">
          <img src={logOut} alt="Logout" />
        </span>
      </a>
    )
  } else {
    const status = location.url == '/login' ? ' is-active' : ''
    return (
      <a className={'button is-primary ' + status} href="/login">
        <span className="icon">
          <img src={logIn} alt="Login" />
        </span>
        <strong>Login</strong>
      </a>
    )
  }
}
