import { useLocation } from 'preact-iso';
import { useState } from 'preact/hooks';

export function NavBar() {
    const [navbarIsActive, setNavbarIsActive]  =
        useState("" as "is-active" | "")

    const { url } = useLocation()
    console.log(url)
    const classOfLink = (mainClass: string, path: string) =>
        url == path ? `${mainClass} is-active` : mainClass

    return (
        <nav class="navbar is-info" role="navigation" aria-label="main navigation">
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
                        Home
                    </a>

                    <a class="navbar-item" href="https://github.com/alexandru/social-publish" target="_blank">
                        GitHub
                    </a>
                </div>

                <div class="navbar-end">
                    <div class="navbar-item">
                        <div class="buttons">
                            <a class={classOfLink("button is-primary", "/login")} href="/login">
                                <strong>Login</strong>
                            </a>
                        </div>
                    </div>
                </div>
            </div>
        </nav>
    );
}