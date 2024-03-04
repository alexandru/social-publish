import './style.css';

export function Home() {
	return (
		<div class="home">
			<section class="section">
				<div class="container">
					<h1 class="title">
						Hello World
					</h1>
					<p class="subtitle">
						My first website with <strong>Bulma</strong>!
					</p>
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
