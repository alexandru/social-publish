import { logoTwitter } from 'ionicons/icons'
import { useState } from 'preact/hooks'
import { updateAuthStatus } from '../../utils/storage'
import { Authorize } from '../../components/Authorize'

export function Account() {
  const [twitterStatus, setTwitterStatus] = useState('Querying...')

  const authorizeTwitter = async () => {
    // redirect
    window.location.href = '/api/twitter/authorize'
  }

  ;(async () => {
    const response = await fetch('/api/twitter/status')
    if (response.status === 200) {
      const json = await response.json()
      if (json?.hasAuthorization) {
        const atDateTime = json.createdAt ? ` at ${new Date(json.createdAt).toLocaleString()}` : ''
        setTwitterStatus(`Connected${atDateTime}`)
      } else {
        setTwitterStatus('Not connected')
      }
      updateAuthStatus((current) => ({ ...current, twitter: json?.hasAuthorization || false }))
      return
    }
    setTwitterStatus(`Error: HTTP ${response.status}`)
    return
  })()

  return (
    <Authorize>
      <div className="account" id="account">
        <section className="section">
          <div className="container block">
            <h1 className="title">Account Settings</h1>
          </div>

          <div className="box">
            <h2 className="subtitle">Social Accounts</h2>
            <button className="button is-link" onClick={authorizeTwitter}>
              <span className="icon">
                <img src={logoTwitter} alt="" style="filter: invert(1)" />
              </span>
              <strong>Connect X (Twitter)</strong>
            </button>
            <p className="help">{twitterStatus}</p>
          </div>
        </section>
      </div>
    </Authorize>
  )
}
