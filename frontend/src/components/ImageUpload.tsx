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
    <div class="block">
      <div class="field">
        <label class="label">Image ({props.id})</label>
        <div class="file">
          <label class="file-label">
            <input
              id={`file_${props.id}`}
              name={`file_${props.id}`}
              class="file-input"
              type="file"
              accept="image/*"
              onInput={onFileChange}
            />
            <span class="file-cta">
              <span class="file-icon">
                <i class="fas fa-upload"></i>
              </span>
              <span class="file-label">Choose an image file…</span>
            </span>
          </label>
        </div>
        <div>
          <p class="help">The image will be displayed in the post.</p>
        </div>
        <div>
          <span>{props.state?.file ? props.state.file.name : ''}</span>
        </div>
      </div>
      <div class="field">
        <label class="label">Alt text for image ({props.id})</label>
        <div class="control">
          <textarea
            id={`altText_${props.id}`}
            name={`altText_${props.id}`}
            rows={2}
            cols={50}
            onInput={onAltTextChange}
            value={props.state.altText || ''}
          ></textarea>
        </div>
      </div>
      <div class="field">
        <button class="button is-small" onClick={onRemove}>
          Remove
        </button>
      </div>
    </div>
  )
}
