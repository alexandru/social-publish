import { html, render } from 'https://esm.sh/htm/preact/standalone'
import PostForm from './PostForm.js';

function App() {
    return html`<${PostForm} />`;
}

render(html`<${App} />`, document.getElementById('app'));
