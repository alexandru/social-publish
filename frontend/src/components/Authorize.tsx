import { ReactNode, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { ModalMessage } from './ModalMessage'
import { getJwtToken } from '../utils/storage'

type Props = {
  children?: ReactNode
}

export function Authorize(props: Props) {
  const [message, setMessage] = useState(
    'You are not authorized to view this page. Please log in...'
  )
  const token = getJwtToken()

  if (!token) {
    const navigate = useNavigate()
    const disable = () => {
      setMessage(null)
      console.log('Redirecting to login...')
      navigate(`/login?redirect=${location.pathname}`)
    }
    return (
      <ModalMessage type="error" isEnabled={!!message} onDisable={disable}>
        {message}
      </ModalMessage>
    )
  }
  return <div className="authorized">{props.children}</div>
}
