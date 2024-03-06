import { Request, Response, NextFunction } from 'express'
import jwt from 'jsonwebtoken'
import logger from '../utils/logger'

export type AuthConfig = {
  serverAuthUsername: string
  serverAuthPassword: string
  serverAuthJwtSecret: string
}

export interface UserPayload {
  username: string;
  iat: number;
  exp: number;
}

declare global {
  namespace Express {
    interface Request {
      user?: UserPayload
    }
  }
}

export class AuthModule {
  constructor(public config: AuthConfig) {}

  middleware = (req: Request, res: Response, next: NextFunction) => {
    const authHeader = req.headers.authorization;
    if (authHeader) {
      const token = authHeader.split(' ')[1];

      jwt.verify(token, this.config.serverAuthJwtSecret, (err, user) => {
        if (err) {
          return res.status(403).send({ error: 'Forbidden' });
        }
        req.user = user as UserPayload;
        next();
      });
    } else {
      res.status(401).send({ error: 'Unauthorized' });
    }
  }

  loginHttpRoute = (req: Request, res: Response) => {
    const username = req.body?.username
    const password = req.body?.password
    if (username === this.config.serverAuthUsername && password === this.config.serverAuthPassword) {
      const payload = { username }
      const token = jwt.sign(payload, this.config.serverAuthJwtSecret, { expiresIn: '2h' })
      res.send({ token })
    } else {
      res.status(401).send({ error: "Invalid credentials" })
    }
  }

  protectedHttpRoute = (req: Request, res: Response) => {
    res.send({ username: req.user?.username })
  }
}
