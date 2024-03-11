export function Home() {
  return (
    <div className="home">
      <section className="section">
        <div className="container block">
          <h1 className="title">Social Publish</h1>
          <p className="subtitle">Spam all your social media accounts at once!</p>
        </div>

        <div className="container box">
          <ul className="content is-medium">
            <li>
              <a href="/rss" target="_blank">
                RSS
              </a>
            </li>
            <li>
              <a
                href="https://github.com/alexandru/social-publish"
                target="_blank"
                rel="noreferrer"
              >
                GitHub
              </a>
            </li>
          </ul>
        </div>
      </section>
    </div>
  )
}
