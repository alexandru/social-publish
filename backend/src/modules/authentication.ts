import { Request, Response, NextFunction } from 'express'
import jwt from 'jsonwebtoken'

export const authUsername = (() => {
  const user = process.env.SERVER_AUTH_USERNAME
  if (!user) throw new Error('SERVER_AUTH_USERNAME is not set')
  return user
})()

export const authPassword = (() => {
  const pass = process.env.SERVER_AUTH_PASSWORD
  if (!pass) throw new Error('SERVER_AUTH_PASSWORD is not set')
  return pass
})()

export const authJwtSecret = (() => {
  const secret = process.env.JWT_SECRET
  if (!secret) throw new Error('JWT_SECRET is not set')
  return secret
})()

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

export const jwtAuth = (req: Request, res: Response, next: NextFunction) => {
  const authHeader = req.headers.authorization;
  if (authHeader) {
    const token = authHeader.split(' ')[1];

    jwt.verify(token, authJwtSecret, (err, user) => {
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

export const loginRoute = (req: Request, res: Response) => {
  const username = req.body?.username
  const password = req.body?.password
  if (username === authUsername && password === authPassword) {
    const payload = { username }
    const token = jwt.sign(payload, authJwtSecret, { expiresIn: '2h' })
    res.send({ token })
  } else {
    res.status(401).send({ error: "Invalid credentials" })
  }
}

export const protectedHttpRoute = (req: Request, res: Response) => {
  res.send({ username: req.user?.username })
}

export default {
  jwtAuth,
  loginRoute,
  protectedHttpRoute,
}
