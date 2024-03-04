import { ComponentChildren, FunctionComponent } from "preact";

export type MessageType = 'info' | 'warning' | 'error'

type ModalMessageProps = {
  type: MessageType
  children?: ComponentChildren,
  isEnabled: boolean,
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

  const title = props.type === 'info' ? 'Info'
    : props.type === 'warning' ? 'Warning'
      : 'Error'
  const clsType = props.type === 'info' ? 'is-info'
    : props.type === 'warning' ? 'is-warning'
      : 'is-danger'

  return (
    <div id="message-modal" class={"modal " + (props.isEnabled ? "is-active" : "")}>
      <div class="modal-background" onClick={handleClick} onKeyDown={handleKeyDown}></div>

      <div class="modal-content">
        <article class={`message is-medium ${clsType}`}>
          <div class="message-header">
            <p>{title}</p>
            <button class="delete" aria-label="delete" onClick={handleClick}></button>
          </div>
          <div class="message-body">
            {props.children}
          </div>
        </article>
      </div>

      <button class="modal-close is-large" aria-label="close" onClick={handleClick}></button>
    </div>
  );
}
