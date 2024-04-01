import { FunctionComponent, useState } from 'react'
import { Authorize } from '../../components/Authorize'
import { useNavigate } from 'react-router-dom'
import { MessageType, ModalMessage } from '../../components/ModalMessage'
import { ImageUpload, SelectedImage } from '../../components/ImageUpload'
import { getAuthStatus } from '../../utils/storage'
import './style.css'

type Target = 'mastodon' | 'twitter' | 'bluesky' | 'linkedin'

type FormData = {
  content?: string
  link?: string
  targets?: Target[]
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
  return <p className="help">Characters left: {280 - text.length}</p>
}

const PostForm: FunctionComponent<Props> = (props: Props) => {
  let formRef = null
  const [data, setData] = useState({} as FormData)
  const [images, setImages] = useState({} as { [id: string]: SelectedImage })
  const hasAuth = getAuthStatus()
  const navigate = useNavigate()

  const addImageComponent = () => {
    const ids = Object.keys(images)
      .map((x) => parseInt(x, 10))
      .sort()
    const newId = ids.length > 0 ? ids[ids.length - 1] + 1 : 1
    setImages({
      ...images,
      [`${newId}`]: { id: newId }
    })
  }

  const removeImageComponent = (id: number) => {
    const newImages = { ...images }
    delete newImages[`${id}`]
    setImages(newImages)
  }

  const onSelectedFile = (value: SelectedImage) => {
    console.log('Selected image', value)
    setImages({ ...images, [`${value.id}`]: value })
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

      if (!data.targets || data.targets.length === 0) {
        props.onError('At least one publication target is required!')
        return
      }

      const imageIDs: string[] = []
      const imagesToUpload = Object.values(images || {})
      for (const image of imagesToUpload) {
        if (image.file) {
          const formData = new FormData()
          formData.append('file', image.file)
          if (image.altText) {
            formData.append('altText', image.altText)
          }

          const response = await fetch('/api/files/upload', {
            method: 'POST',
            body: formData
          })
          if ([401, 403].includes(response.status)) {
            navigate(`/login?error=${response.status}&redirect=/form`)
            return
          }
          if (response.status !== 200) {
            props.onError(
              `Error uploading image: HTTP ${response.status} / ${await response.text()}`
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
            'Content-Type': 'application/json'
          },
          body: JSON.stringify({
            ...data,
            images: imageIDs
          })
        })
        if ([401, 403].includes(response.status)) {
          navigate(`/login?error=${response.status}&redirect=/form`)
          return
        }
        if (response.status !== 200) {
          props.onError(`Error submitting form: HTTP ${response.status} / ${await response.text()}`)
          return
        }
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

  const onTargetCheck = (key: Target) => (event) => {
    const newData = { ...data }
    if (event.target.checked) {
      if (!newData?.targets?.includes(key)) newData.targets = [...(data.targets || []), key]
    } else {
      newData.targets = (data.targets || []).filter((x) => x !== key)
    }
    if (newData.targets && newData.targets.length === 0) {
      delete newData.targets
    }
    setData(newData)
  }

  const onCheckbox = (fieldName: keyof FormData) => (event) => {
    setData({
      ...data,
      [fieldName]: event.target.checked ? '1' : undefined
    })
  }

  return (
    <form ref={(el) => (formRef = el)} onSubmit={onSubmit} className="box">
      <fieldset id="post-form-fieldset">
        <div className="field">
          <label className="label">Content</label>
          <div className="control">
            <textarea
              id="content"
              name="content"
              rows={4}
              cols={50}
              className="textarea"
              onInput={onInput('content')}
              required
            />
          </div>
          <CharsLeft data={data} />
        </div>
        <div className="field">
          <label className="label">Highlighted link (optional)</label>
          <div className="control">
            <input
              type="link"
              className="input"
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
            key={image.id}
            id={image.id}
            state={image}
            onSelect={onSelectedFile}
            onRemove={removeImageComponent}
          />
        ))}
        <div className="field">
          <label className="checkbox">
            <input
              type="checkbox"
              id="mastodon"
              name="mastodon"
              onInput={onTargetCheck('mastodon')}
            />{' '}
            Mastodon
          </label>
        </div>
        <div className="field">
          <label className="checkbox">
            <input type="checkbox" id="bluesky" name="bluesky" onInput={onTargetCheck('bluesky')} />{' '}
            Bluesky
          </label>
        </div>
        <div className="field">
          <label className="checkbox">
            {hasAuth.twitter ? (
              <input
                type="checkbox"
                id="twitter"
                name="twitter"
                onInput={onTargetCheck('twitter')}
              />
            ) : (
              <input type="checkbox" id="twitter" name="twitter" disabled />
            )}{' '}
            Twitter
          </label>
        </div>
        <div className="field">
          <label className="checkbox">
            <input
              type="checkbox"
              id="linkedin"
              name="linkedin"
              onInput={onTargetCheck('linkedin')}
            />{' '}
            LinkedIn
          </label>
          <p className="help">
            <a href="/rss/target/linkedin" target="_blank">
              Via RSS feed
            </a>{' '}
            (needs{' '}
            <a href="https://ifttt.com" target="_blank" rel="noreferrer">
              ifttt.com
            </a>{' '}
            setup)
          </p>
        </div>
        <div className="field">
          <label className="checkbox">
            <input
              type="checkbox"
              id="cleanupHtml"
              name="cleanupHtml"
              onInput={onCheckbox('cleanupHtml')}
            />{' '}
            cleanup HTML
          </label>
        </div>
        <input className="button" type="reset" value="Reset" id="post-form-reset-button" />{' '}
        {Object.keys(images).length < 4 ? (
          <button type="button" className="button" onClick={addImageComponent}>
            Add image
          </button>
        ) : null}{' '}
        <input className="button is-primary" type="submit" value="Submit" />
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
      <div className="publish-form">
        <section className="section">
          <div className="container block">
            <h1 className="title">Social Publish</h1>
            <p className="subtitle">Spam all your social media accounts at once!</p>
          </div>

          <div className="container">
            <PostForm onError={showModal('error')} onInfo={showModal('info')} />
          </div>
        </section>
      </div>
    </Authorize>
  )
}
