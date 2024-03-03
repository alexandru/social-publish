
import { html, Component } from 'https://esm.sh/htm/preact/standalone'

function CharsLeft(props) {
    const content = props.content || ''
    return html`<div class="chars-left">Characters left: ${280 - content.length}</div>`
}

class PostForm extends Component {
    state = {
        data: {}
    };

    onSubmit = async (event) => {
        event.preventDefault()
        const data = this.state?.data || {}

        if (!data.content) {
            alert('Content is required!')
            return
        }
        if (!data.mastodon && !data.bluesky) {
            alert('At least one social network is required!')
            return
        }

        const body = Object.keys(data).map(key => encodeURIComponent(key) + '=' + encodeURIComponent(data[key])).join('&');
        this.state.data = {}

        try {
            const response = await fetch('/api/multiple/post', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded'
                },
                body
            })

            if (response.status !== 200) {
                alert('Error submitting form: HTTP ' + response.status + ' / ' + await response.text())
                return
            } else {
                alert('Form was submited: ' + await response.text())
            }
        } catch (e) {
            alert('Exception while submitting form!')
            console.error(e)
        }
    };

    onInput = (fieldName) => (event) => {
        console.log('onInput', fieldName, event.target.value)
        const newState = {
            ...this.state,
            data: {
                ...this.state.data,
                [fieldName]: event.target.value
            }
        }
        this.setState(newState)
    };

    onCheckbox = (fieldName) => (event) => {
        console.log('onCheckbox', fieldName, event.target.checked)
        const newState = this.state
        if (event.target.checked)
            newState.data[fieldName] = "1"
        else
            delete newState.data[fieldName]
        this.setState(newState)
    };

    charsLeft = () => {
        const content = this.state?.data?.content || ''
        console.log('charsLeft', content)
        return content > 0
            ? html`Characters left: ${this.state.content ? 280 - this.state.content.length : 280}`
            : html``
    }

    render() {
        return html`
        <form onSubmit=${this.onSubmit}>
          <p class="textbox">
            <textarea id="content" name="content" rows="4" cols="50" onInput=${this.onInput("content")}></textarea>
            <${CharsLeft} content=${this.state?.data?.content} />
          </p>
          <p>
            <input type="checkbox" id="mastodon" name="mastodon" onInput=${this.onCheckbox("mastodon")} />
            <label for="mastodon">Mastodon</label>
            <input type="checkbox" id="bluesky" name="bluesky" onInput=${this.onCheckbox("bluesky")} />
            <label for="bluesky">Bluesky</label>
          </p>
          <p>
            <input type="checkbox" id="cleanupHtml" name="cleanupHtml" onInput=${this.onCheckbox("cleanupHtml")} />
            <label for="cleanupHtml">cleanup HTML</label>
          </p>
          <input type="submit" value="Submit" />
        </form>
        `
    }
}

export default PostForm
