import { useLocation } from 'preact-iso';
import { useState } from 'preact/hooks';
import { logOut, logIn, homeOutline, play, logoGithub } from 'ionicons/icons'

export function NavBar() {
    const [navbarIsActive, setNavbarIsActive]  =
        useState("" as "is-active" | "")

    const { url } = useLocation()
    const classOfLink = (mainClass: string, path: string) =>
        url == path ? `${mainClass} is-active` : mainClass

    const isLoggedIn = !!sessionStorage.getItem('jwtToken')

    return (
        <nav class="navbar is-primary" role="navigation" aria-label="main navigation">
            <div class="navbar-brand">
                <a role="button"
                    class={"navbar-burger " + navbarIsActive}
                    onClick={() => setNavbarIsActive(
                        navbarIsActive == "is-active" ? "" : "is-active"
                    )}
                    aria-label="menu"
                    aria-expanded="false"
                    data-target="navbarBasicExample"
                    >
                    <span aria-hidden="true"></span>
                    <span aria-hidden="true"></span>
                    <span aria-hidden="true"></span>
                </a>
            </div>

            <div id="navbarBasicExample" class={"navbar-menu " + navbarIsActive}>
                <div class="navbar-start">
                    <a class={classOfLink("navbar-item", "/")} href="/">
                        <span class="icon">
                          <img src={homeOutline} alt="Home" />
                        </span>
                        <strong>
                          Home
                        </strong>
                    </a>
                    <IfLoggedIn isLoggedIn={isLoggedIn}>
                      <a class={classOfLink("navbar-item", "/form")} href="/form">
                          <span class="icon">
                            <img src={play} alt="Publish" />
                          </span>
                          <strong>
                            Publish
                          </strong>
                      </a>
                    </IfLoggedIn>
                    <a class="navbar-item" href="https://github.com/alexandru/social-publish" target="_blank">
                        <span class="icon">
                          <img src={logoGithub} alt="Help" />
                        </span>
                        <strong>
                          Help
                        </strong>
                    </a>
                </div>

                <div class="navbar-end">
                    <div class="navbar-item">
                        <div class="buttons">
                          <LoginOrLogoutButton isLoggedIn={isLoggedIn} />
                        </div>
                    </div>
                </div>
            </div>
        </nav>
    );
}

function IfLoggedIn(props: { isLoggedIn: boolean, children: any }) {
  return props.isLoggedIn ? props.children : null
}

function LoginOrLogoutButton(props: { isLoggedIn: boolean }) {
  const location = useLocation()
  const onLogout = () => {
    sessionStorage.removeItem('jwtToken')
    window.location.href = "/"
    return
  }

  if (props.isLoggedIn) {
    return (
      <a class="button is-primary" onClick={onLogout}>
        <strong>Logout</strong>
        <span class="icon">
          <img src={logOut} alt="Logout" />
        </span>
      </a>
    )
  } else {
    const status = location.url == "/login" ? " is-active" : ""
    return (
      <a class={"button is-primary " + status} href="/login">
        <span class="icon">
          <img src={logIn} alt="Login" />
        </span>
        <strong>Login</strong>
      </a>
    )
  }
}

