import { Request, Response, NextFunction } from 'express'
import jwt from 'jsonwebtoken'
import { TwitterApiModule } from './twitter-api'
import { writeErrorToResponse } from '../models/errors'

export type AuthConfig = {
  serverAuthUsername: string
  serverAuthPassword: string
  serverAuthJwtSecret: string
}

export interface UserPayload {
  username: string
  iat: number
  exp: number
}

export class AuthModule {
  constructor(
    private config: AuthConfig,
    private twitterApi: TwitterApiModule
  ) {}

  middleware = (req: Request, res: Response, next: NextFunction) => {
    let token: string | null = null
    const authHeader = req.headers.authorization
    if (authHeader) {
      token = authHeader.split(' ')[1]
    }
    if (!token && req.query?.access_token) {
      token = `${req.query.access_token}`
    }
    if (!token && req.parsedCookies?.access_token) {
      token = req.parsedCookies.access_token
    }
    if (!token) {
      return res.status(401).send({ error: 'Unauthorized' })
    }
    jwt.verify(token, this.config.serverAuthJwtSecret, (err, user) => {
      if (err) {
        return res.status(403).send({ error: 'Forbidden' })
      }
      req.user = user as UserPayload
      req.jwtToken = token || undefined
      next()
    })
  }

  loginHttpRoute = async (req: Request, res: Response) => {
    const username = req.body?.username
    const password = req.body?.password
    if (
      username === this.config.serverAuthUsername &&
      password === this.config.serverAuthPassword
    ) {
      const payload = { username }
      const token = jwt.sign(payload, this.config.serverAuthJwtSecret, { expiresIn: '168h' })

      res.send({
        token,
        hasAuth: {
          twitter: await this.twitterApi.hasTwitterAuth()
        }
      })
    } else {
      res.status(401).send({ error: 'Invalid credentials' })
    }
  }

  protectedHttpRoute = (req: Request, res: Response) => {
    res.send({ username: req.user?.username })
  }
}
