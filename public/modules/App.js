
import { html } from 'https://esm.sh/htm/preact/standalone'
import PostForm from './PostForm.js';

function App(props) {
    return html`
        <h1>Post a New Social Message</h1>
        <${PostForm} />
        `;
}

export default App
