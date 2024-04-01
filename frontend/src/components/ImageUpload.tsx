import React from 'react'

export type SelectedImage = {
  id: number
  file?: File
  altText?: string
}

export type ImageUploadProps = {
  id: number
  state: SelectedImage
  onSelect: (value: SelectedImage) => void
  onRemove: (id: number) => void
}

export const ImageUpload = (props: ImageUploadProps) => {
  const onFileChange = (event) => {
    event.preventDefault()
    props.onSelect({
      ...props.state,
      file: event.target.files[0]
    })
  }

  const onAltTextChange = (event) => {
    event.preventDefault()
    props.onSelect({
      ...props.state,
      altText: event.target.value
    })
  }

  const onRemove = (event) => {
    event.preventDefault()
    props.onRemove(props.id)
  }

  return (
    <div className="block">
      <div className="field">
        <label className="label">Image ({props.id})</label>
        <div className="file">
          <label className="file-label">
            <input
              id={`file_${props.id}`}
              name={`file_${props.id}`}
              className="file-input"
              type="file"
              accept="image/*"
              onInput={onFileChange}
            />
            <span className="file-cta">
              <span className="file-icon">
                <i className="fas fa-upload" />
              </span>
              <span className="file-label">Choose an image file…</span>
            </span>
          </label>
        </div>
        <div>
          <p className="help">The image will be displayed in the post.</p>
        </div>
        <div>
          <span>{props.state?.file ? props.state.file.name : ''}</span>
        </div>
      </div>
      <div className="field">
        <label className="label">Alt text for image ({props.id})</label>
        <div className="control">
          <textarea
            id={`altText_${props.id}`}
            name={`altText_${props.id}`}
            rows={2}
            cols={50}
            onInput={onAltTextChange}
            value={props.state.altText || ''}
            className="textarea"
          />
        </div>
      </div>
      <div className="field">
        <button className="button is-small" onClick={onRemove}>
          Remove
        </button>
      </div>
    </div>
  )
}
