import { ComponentChildren } from 'preact'
import { useLocation } from 'preact-iso'
import { ModalMessage } from './ModalMessage'
import { useState } from 'preact/hooks'
import { getJwtToken } from '../utils/storage'

type Props = {
  children?: ComponentChildren
}

export function Authorize(props: Props) {
  const location = useLocation()
  const token = getJwtToken()

  if (!token) {
    const [message, setMessage] = useState(
      'You are not authorized to view this page. Please log in...'
    )
    const disable = () => {
      setMessage(null)
      location.route('/login?redirect=' + location.url)
    }
    return (
      <ModalMessage type="error" isEnabled={!!message} onDisable={disable}>
        {message}
      </ModalMessage>
    )
  }
  return <div class="authorized">{props.children}</div>
}
