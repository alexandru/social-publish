import { Component, FunctionalComponent } from 'preact'
import { Authorize } from '../../components/Authorize'
import './style.css'
import { useLocation } from 'preact-iso'
import { useState } from 'preact/hooks'
import { MessageType, ModalMessage } from '../../components/ModalMessage'
import { ImageUpload, SelectedImage } from '../../components/ImageUpload'

type FormData = {
  content?: string
  link?: string
  mastodon?: string
  bluesky?: string
  rss?: string
  cleanupHtml?: string
}

type CharsLeftProps = {
  data: FormData
}

type Props = {
  onError: (error: string) => void
  onInfo: (info: any) => void
}

function CharsLeft(props: CharsLeftProps) {
  const text = [props.data?.content, props.data?.link].filter((x) => x && x.length > 0).join('\n\n')
  return <p class="help">Characters left: {280 - text.length}</p>
}

const PostForm: FunctionalComponent<Props> = (props: Props) => {
  let formRef = null
  const [data, setData] = useState({} as FormData)
  const [images, setImages] = useState({} as { [id: string]: SelectedImage })
  const location = useLocation()

  const addImageComponent = () => {
    const ids = Object.keys(images)
      .map((x) => parseInt(x))
      .sort()
    const newId = ids.length > 0 ? ids[ids.length - 1] + 1 : 1
    setImages({
      ...images,
      ['' + newId]: { id: newId }
    })
  }

  const removeImageComponent = (id: number) => {
    const newImages = { ...images }
    delete newImages['' + id]
    setImages(newImages)
  }

  const onSelectedFile = (value: SelectedImage) => {
    console.log('Selected image', value)
    setImages({ ...images, ['' + value.id]: value })
  }

  const withDisabledForm = async (f: () => Promise<void>) => {
    const fieldset = document.getElementById('post-form-fieldset')
    fieldset.setAttribute('disabled', 'true')
    try {
      await f()
    } finally {
      fieldset.removeAttribute('disabled')
    }
  }

  const onSubmit = async (event) => {
    event.preventDefault()
    return await withDisabledForm(async () => {
      if (!data.content) {
        props.onError('Content is required!')
        return
      }

      if (!data.mastodon && !data.bluesky && !data.rss) {
        props.onError('At least one publication target is required!')
        return
      }

      const imageIDs: string[] = []
      const imagesToUpload = Object.values(images || {})
      for (const image of imagesToUpload) {
        if (image.file) {
          const formData = new FormData()
          formData.append('file', image.file)
          formData.append('altText', image.altText)

          const response = await fetch('/api/files/upload', {
            method: 'POST',
            headers: {
              Authorization: `Bearer ${sessionStorage.getItem('jwtToken')}`
            },
            body: formData
          })
          if ([401, 403].includes(response.status)) {
            location.route(`/login?error=${response.status}&redirect=/form`)
            return
          }
          if (response.status !== 200) {
            props.onError(
              'Error uploading image: HTTP ' + response.status + ' / ' + (await response.text())
            )
            return
          }
          const json = await response.json()
          console.log('Uploaded image', json)
          imageIDs.push(json.uuid)
        }
      }

      const body = {
        ...data,
        images: imageIDs
      }
      console.log('Submitting form', body)

      try {
        const response = await fetch('/api/multiple/post', {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${sessionStorage.getItem('jwtToken')}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            ...data,
            images: imageIDs
          })
        })
        if ([401, 403].includes(response.status)) {
          location.route(`/login?error=${response.status}&redirect=/form`)
          return
        }
        if (response.status !== 200) {
          props.onError(
            'Error submitting form: HTTP ' + response.status + ' / ' + (await response.text())
          )
          return
        } else {
          console.log('New post created successfully', await response.json())
          // Resetting form...
          formRef.reset()
          setData({})
          setImages({})
          // Signaling success...
          props.onInfo(
            <div>
              <p>New post created successfully!</p>
              <p>
                View the{' '}
                <a href="/rss" target="_blank">
                  RSS feed?
                </a>
              </p>
            </div>
          )
          return
        }
      } catch (e) {
        props.onError('Unexpected exception while submitting form!')
        console.error(e)
      }
    })
  }

  const onInput = (fieldName: keyof FormData) => (event) => {
    setData({
      ...data,
      [fieldName]: event.target.value
    })
  }

  const onCheckbox = (fieldName: keyof FormData) => (event) => {
    setData({
      ...data,
      [fieldName]: event.target.checked ? '1' : undefined
    })
  }

  return (
    <form ref={(el) => (formRef = el)} onSubmit={onSubmit} class="box">
      <fieldset id="post-form-fieldset">
        <div class="field">
          <label class="label">Content</label>
          <div class="control">
            <textarea
              id="content"
              name="content"
              rows={4}
              cols={50}
              class="textarea"
              onInput={onInput('content')}
              required
            ></textarea>
          </div>
          <CharsLeft data={data} />
        </div>
        <div class="field">
          <label class="label">Highlighted link (optional)</label>
          <div class="control">
            <input
              type="link"
              class="input"
              placeholder="https://example.com/..."
              id="link"
              name="link"
              onInput={onInput('link')}
              pattern="https?://.+"
            />
          </div>
        </div>
        {Object.values(images).map((image) => (
          <ImageUpload
            id={image.id}
            state={image}
            onSelect={onSelectedFile}
            onRemove={removeImageComponent}
          />
        ))}
        <div class="field">
          <label class="checkbox">
            <input type="checkbox" id="mastodon" name="mastodon" onInput={onCheckbox('mastodon')} />{' '}
            Mastodon
          </label>
        </div>
        <div class="field">
          <label class="checkbox">
            <input type="checkbox" id="bluesky" name="bluesky" onInput={onCheckbox('bluesky')} />{' '}
            Bluesky
          </label>
        </div>
        <div class="field">
          <label class="checkbox">
            <input type="checkbox" id="rss" name="rss" onInput={onCheckbox('rss')} /> RSS
          </label>
        </div>
        <div class="field">
          <label class="checkbox">
            <input
              type="checkbox"
              id="cleanupHtml"
              name="cleanupHtml"
              onInput={onCheckbox('cleanupHtml')}
            />{' '}
            cleanup HTML
          </label>
        </div>
        <input class="button" type="reset" value="Reset" id="post-form-reset-button" />{' '}
        {Object.keys(images).length < 4 ? (
          <button type="button" class="button" onClick={addImageComponent}>
            Add image
          </button>
        ) : null}{' '}
        <input class="button is-primary" type="submit" value="Submit" />
      </fieldset>
    </form>
  )
}

export function PublishFormPage() {
  const [message, setMessage] = useState(
    null as {
      type: MessageType
      text: string
    }
  )
  const modal = message ? (
    <ModalMessage type={message.type} isEnabled={!!message} onDisable={() => setMessage(null)}>
      {message.text}
    </ModalMessage>
  ) : null
  const showModal = (type: MessageType) => (text: string) => setMessage({ type, text })

  return (
    <Authorize>
      {modal}
      <div class="publish-form">
        <section class="section">
          <div class="container block">
            <h1 class="title">Social Publish</h1>
            <p class="subtitle">Spam all your social media accounts at once!</p>
          </div>

          <div class="container">
            <PostForm onError={showModal('error')} onInfo={showModal('info')} />
          </div>
        </section>
      </div>
    </Authorize>
  )
}
