import { Component, FunctionalComponent } from 'preact';
import { Authorize } from '../../components/Authorize';
import './style.css';
import { useLocation } from 'preact-iso';
import { useState } from 'preact/hooks';
import { MessageType, ModalMessage } from '../../components/ModalMessage';

type FormData = {
  content?: string,
  link?: string,
  mastodon?: string,
  bluesky?: string,
  rss?: string,
  cleanupHtml?: string
}

type CharsLeftProps = {
  data: FormData
}

type Props = {
  onError: (error: string) => void,
  onInfo: (info: any) => void
}

function CharsLeft(props: CharsLeftProps) {
  const text = [props.data?.content, props.data?.link]
    .filter(x => x && x.length > 0)
    .join("\n\n")
  return (
    <p class="help">
      Characters left: {280 - text.length}
    </p>
  );
}

const PostForm: FunctionalComponent<Props> = (props: Props) => {
  let formRef = null
  const [data, setData] = useState({} as FormData)
  const location = useLocation()

  const onSubmit = async (event) => {
    event.preventDefault()

    if (!data.content) {
      props.onError('Content is required!')
      return
    }
    if (!data.mastodon && !data.bluesky && !data.rss) {
      props.onError('At least one publication target is required!')
      return
    }

    const body = Object
      .keys(data)
      .map(key => encodeURIComponent(key) + '=' + encodeURIComponent(data[key]))
      .join('&');

    setData({})
    formRef.reset()

    try {
      const response = await fetch('/api/multiple/post', {
        method: 'POST',
        headers: {
          "Authorization": `Bearer ${sessionStorage.getItem('jwtToken')}`,
          'Content-Type': 'application/x-www-form-urlencoded'
        },
        body
      })
      if ([401, 403].includes(response.status)) {
        location.route(`/login?error=${response.status}&redirect=/form`)
        return
      }
      if (response.status !== 200) {
        props.onError('Error submitting form: HTTP ' + response.status + ' / ' + await response.text())
        return
      } else {
        props.onInfo(
          <div>
            <p>Form submitted successfully!</p>
            <p>View the <a href="/rss" target="_blank">RSS feed?</a></p>
          </div>
        )
        return
      }
    } catch (e) {
      props.onError('Unexpected exception while submitting form!')
      console.error(e)
    }
  }

  const onInput = (fieldName: keyof FormData) => event => {
    setData({
      ...data,
      [fieldName]: event.target.value
    })
  }

  const onCheckbox = (fieldName: keyof FormData) => (event) => {
    setData({
      ...data,
      [fieldName]: event.target.checked ? "1" : undefined
    })
  }

  return (
    <form ref={el => formRef = el} onSubmit={onSubmit} class="box">
      <div class="field">
        <label class="label">Content</label>
        <div class="control">
          <textarea id="content" name="content" rows={4} cols={50}
            class="textarea"
            onInput={onInput("content")}
            required></textarea>
        </div>
        <CharsLeft data={data} />
      </div>
      <div class="field">
        <label class="label">Highlighted link (optional)</label>
        <div class="control">
        <input type="link"
          class="input"
          placeholder="https://example.com/..."
          id="link" name="link"
          onInput={onInput("link")}
          pattern="https?://.+" />
        </div>
      </div>
      <div class="field">
        <label class="checkbox">
          <input type="checkbox" id="mastodon" name="mastodon" onInput={onCheckbox("mastodon")} /> Mastodon
        </label>
      </div>
      <div class="field">
        <label class="checkbox">
          <input type="checkbox" id="bluesky" name="bluesky" onInput={onCheckbox("bluesky")} /> Bluesky
        </label>
      </div>
      <div class="field">
        <label class="checkbox">
          <input type="checkbox" id="rss" name="rss" onInput={onCheckbox("rss")} /> RSS
        </label>
      </div>
      <div class="field">
        <label class="checkbox">
          <input type="checkbox" id="cleanupHtml" name="cleanupHtml" onInput={onCheckbox("cleanupHtml")} /> cleanup HTML
        </label>
      </div>
      <input class="button" type="reset" value="Reset" /> <input class="button is-primary" type="submit" value="Submit" />
    </form>
  )
}

export function PublishFormPage() {
  const [message, setMessage] =
    useState(null as {
      type: MessageType,
      text: string
    })
  const modal = message
    ? (
      <ModalMessage type={message.type} isEnabled={!!message} onDisable={() => setMessage(null)}>
        {message.text}
      </ModalMessage>
    ) : null
  const showModal = (type: MessageType) => (text: string) =>
    setMessage({ type, text })

  return (
    <Authorize>
      {modal}
      <div class="publish-form">
        <section class="section">
          <div class="container block">
            <h1 class="title">
              Social Publish
            </h1>
            <p class="subtitle">
              Spam all your social media accounts at once!
            </p>
          </div>

          <div class="container">
            <PostForm onError={showModal("error")} onInfo={showModal("info")} />
          </div>
        </section>
      </div>
    </Authorize>
  );
}
