import { ComponentChildren } from 'preact'

export type MessageType = 'info' | 'warning' | 'error'

type ModalMessageProps = {
  type: MessageType
  children?: ComponentChildren
  isEnabled: boolean
  onDisable: () => void
}

export const ModalMessage = (props: ModalMessageProps) => {
  const handleClick = () => {
    props.onDisable()
  }

  const handleKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'Escape' || event.keyCode === 27) {
      props.onDisable()
    }
  }

  const title = props.type === 'info' ? 'Info' : props.type === 'warning' ? 'Warning' : 'Error'
  const clsType =
    props.type === 'info' ? 'is-info' : props.type === 'warning' ? 'is-warning' : 'is-danger'

  return (
    <div id="message-modal" className={`modal ${props.isEnabled ? 'is-active' : ''}`}>
      <div className="modal-background" onClick={handleClick} onKeyDown={handleKeyDown} />

      <div className="modal-content">
        <article className={`message is-medium ${clsType}`}>
          <div className="message-header">
            <p>{title}</p>
            <button className="delete" aria-label="delete" onClick={handleClick} />
          </div>
          <div className="message-body">{props.children}</div>
        </article>
      </div>

      <button className="modal-close is-large" aria-label="close" onClick={handleClick} />
    </div>
  )
}
