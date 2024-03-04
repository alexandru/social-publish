export function Home() {
	return (
    <div class="home">
    <section class="section">
      <div class="container block">
        <h1 class="title">
          Social Publish
        </h1>
        <p class="subtitle">
          Spam all your social media accounts at once!
        </p>
      </div>

      <div class="container box">
        <ul class="content is-medium">
          <li><a href="/rss" target="_blank">RSS</a></li>
          <li><a href="https://github.com/alexandru/social-publish" target="_blank">GitHub</a></li>
        </ul>
      </div>
    </section>
  </div>
	);
}

function Resource(props) {
	return (
		<a href={props.href} target="_blank" class="resource">
			<h2>{props.title}</h2>
			<p>{props.description}</p>
		</a>
	);
}
