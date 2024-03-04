import { render } from 'preact';
import { LocationProvider, Router, Route } from 'preact-iso';

import { Header } from './components/Header.jsx';
import { Home } from './pages/Home/index.jsx';
import { NotFound } from './pages/_404.jsx';
import './style.css';
import { NavBar } from './components/NavBar.js';

export function App() {
	// <LocationProvider>
	// 	<Header />
	// 	<main>
	// 		<Router>
	// 			<Route path="/" component={Home} />
	// 			<Route default component={NotFound} />
	// 		</Router>
	// 	</main>
	// </LocationProvider>
	return (
		<LocationProvider>
			<NavBar />
			<main>
				<Router>
					<Route path="/" component={Home} />
					<Route default component={NotFound} />
				</Router>
			</main>
		</LocationProvider>

	);
}

render(<App />, document.getElementById('app'));
