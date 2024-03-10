import { UserPayload } from './modules/authentication'

declare global {
  namespace Express {
    interface Request {
      user?: UserPayload
      jwtToken?: string
      parsedCookies?: Record<string, string>
    }
  }
}
