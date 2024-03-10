import { FunctionalComponent } from 'preact'
import { logoTwitter } from 'ionicons/icons'
import { useState } from 'preact/hooks'
import { updateAuthStatus } from '../../utils/storage'

export function Account() {
  const [twitterStatus, setTwitterStatus] = useState("Querying...")

  const authorizeTwitter = async () => {
    // redirect
    window.location.href = '/api/twitter/authorize'
  }

  (async () => {
    const response = await fetch('/api/twitter/status')
    if (response.status === 200) {
      const json = await response.json()
      setTwitterStatus("Connected at " + new Date(json.createdAt).toLocaleString())
      updateAuthStatus(current => ({ ...current, twitter: true }))
      return
    } else if (response.status === 404) {
      setTwitterStatus("Not connected")
      updateAuthStatus(current => ({ ...current, twitter: false }))
      return
    } else {
      setTwitterStatus("Error: HTTP " + response.status)
      return
    }
  })()

  return (
    <div class="account" id="account">
      <section class="section">
        <div class="container block">
          <h1 class="title">Account Settings</h1>
        </div>

        <div class="box">
          <h2 class="subtitle">Social Accounts</h2>
          <button class="button is-link" onClick={authorizeTwitter}>
            <span class="icon">
              <img src={logoTwitter} alt="" style="filter: invert(1)" />
            </span>
            <strong>Connect X (Twitter)</strong>
          </button>
          <p class="help">{twitterStatus}</p>
        </div>
      </section>
    </div>
  )
}
