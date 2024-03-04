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
  onInfo: (info: string) => void
}

function CharsLeft(props: CharsLeftProps) {
  const text = [props.data?.content, props.data?.link]
    .filter(x => x && x.length > 0)
    .join("\n\n")
  return (
    <div class="chars-left">
      Characters left: {280 - text.length}
    </div>
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
      props.onError('At least one social network is required!')
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
      if (response.status === 401) {
        location.route('/login?error=401')
        return
      }
      if (response.status !== 200) {
        props.onError('Error submitting form: HTTP ' + response.status + ' / ' + await response.text())
        return
      } else {
        props.onInfo('Form submitted successfully!')
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
    <form ref={el => formRef = el} onSubmit={onSubmit}>
      <p class="textbox">
        <textarea id="content" name="content" rows={4} cols={50}
          onInput={onInput("content")}
          required></textarea>
      </p>
      <p class="field">
        <input type="link"
          placeholder="Highlighted URL (optional)"
          id="link" name="link"
          onInput={onInput("link")}
          pattern="https?://.+"
        />
      </p>
      <p>
        <CharsLeft data={data} />
      </p>
      <p>
        <input type="checkbox" id="mastodon" name="mastodon" onInput={onCheckbox("mastodon")} />
        <label for="mastodon">Mastodon</label>
        <input type="checkbox" id="bluesky" name="bluesky" onInput={onCheckbox("bluesky")} />
        <label for="bluesky">Bluesky</label>
        <input type="checkbox" id="rss" name="rss" onInput={onCheckbox("rss")} />
        <label for="rss">RSS</label>
      </p>
      <p>
        <input type="checkbox" id="cleanupHtml" name="cleanupHtml" onInput={onCheckbox("cleanupHtml")} />
        <label for="cleanupHtml">cleanup HTML</label>
      </p>
      <input type="submit" value="Submit" />
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
          <div class="container">
            <h1 class="title">
              Social Publish
            </h1>
            <p class="subtitle">
              Spam all your social media accounts at once!
            </p>
          </div>
        </section>
        <section class="section">
          <PostForm onError={showModal("error")} onInfo={showModal("info")} />
        </section>
      </div>
    </Authorize>
  );
}
