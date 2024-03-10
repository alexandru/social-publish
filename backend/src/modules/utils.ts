import { Request, Response, NextFunction } from 'express'

export const parseCookiesMiddleware = (req: Request, res: Response, next: NextFunction) => {
  const cookies = req.headers.cookie?.split('; ').reduce(
    (prev, current) => {
      const [name, value] = current.split('=')
      prev[name] = value
      return prev
    },
    {} as Record<string, string>
  )
  req.parsedCookies = cookies || {}
  next()
}
