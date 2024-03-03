
import { html, Component } from 'https://esm.sh/htm/preact/standalone'

function CharsLeft(props) {
    const text = [props.data?.content, props.data?.link]
        .filter(x => x && x.length > 0)
        .join("\n\n")
    return html`<div class="chars-left">Characters left: ${280 - text.length}</div>`
}

class PostForm extends Component {
    formRef = null
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
        if (!data.mastodon && !data.bluesky && !data.rss) {
            alert('At least one social network is required!')
            return
        }

        const body = Object.keys(data).map(key => encodeURIComponent(key) + '=' + encodeURIComponent(data[key])).join('&');
        this.state.data = {}
        this.formRef.reset()

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
        const newState = this.state
        if (event.target.checked)
            newState.data[fieldName] = "1"
        else
            delete newState.data[fieldName]
        this.setState(newState)
    };

    render() {
        return html`
        <h1>Post a New Social Message</h1>

        <form ref=${el => this.formRef = el} onSubmit=${this.onSubmit}>
          <p class="textbox">
            <textarea id="content" name="content" rows="4" cols="50"
                onInput=${this.onInput("content")}
                required></textarea>
          </p>
          <p class="field">
            <input type="link"
                placeholder="Optional URL"
                id="link" name="link"
                onInput=${this.onInput("link")}
                pattern="https?://.+"
                />
          </p>
          <p>
            <${CharsLeft} data=${this.state?.data} />
          </p>
          <p>
            <input type="checkbox" id="mastodon" name="mastodon" onInput=${this.onCheckbox("mastodon")} />
            <label for="mastodon">Mastodon</label>
            <input type="checkbox" id="bluesky" name="bluesky" onInput=${this.onCheckbox("bluesky")} />
            <label for="bluesky">Bluesky</label>
            <input type="checkbox" id="rss" name="rss" onInput=${this.onCheckbox("rss")} />
            <label for="rss">RSS</label>
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
