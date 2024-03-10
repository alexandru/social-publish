import { render } from 'preact'
import { LocationProvider, Router, Route } from 'preact-iso'

import { PublishFormPage } from './pages/PublishFormPage/index.js'
import { NotFound } from './pages/_404.jsx'
import './style.css'
import { NavBar } from './components/NavBar.js'
import { Login } from './pages/Login/index.js'
import { Home } from './pages/Home.js'
import { Account } from './pages/Account/index.js'

export function App() {
  return (
    <LocationProvider>
      <NavBar />
      <main>
        <Router>
          <Route path="/" component={Home} />
          <Route path="/form" component={PublishFormPage} />
          <Route path="/login" component={Login} />
          <Route path="/account" component={Account} />
          <Route default component={NotFound} />
        </Router>
      </main>
    </LocationProvider>
  )
}

render(<App />, document.getElementById('app'))
