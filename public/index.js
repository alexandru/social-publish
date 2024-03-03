import { html, render } from 'https://esm.sh/htm/preact/standalone'
import App from "./modules/App.js"

render(html`<${App} />`, document.getElementById('app'));
