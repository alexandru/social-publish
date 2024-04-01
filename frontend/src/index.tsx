import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { PublishFormPage } from './pages/PublishFormPage/index.js'
import { NotFound } from './pages/_404.jsx'
import { NavBar } from './components/NavBar.js'
import { Login } from './pages/Login/index.js'
import { Home } from './pages/Home.js'
import { Account } from './pages/Account/index.js'
import ReactDOM from 'react-dom/client'
import './style.css'

export function App() {
  return (
    <BrowserRouter>
      <NavBar />
      <main>
        <Routes>
          <Route index path="/" element={<Home />} />
          <Route path="/form" element={<PublishFormPage />} />
          <Route path="/login" element={<Login />} />
          <Route path="/account" element={<Account />} />
          <Route path="*" element={<NotFound />} />
        </Routes>
      </main>
    </BrowserRouter>
  )
}

ReactDOM.createRoot(document.getElementById('app') as HTMLElement).render(<App />)
