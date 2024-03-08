import { Request, Response } from 'express'

const pingHttpRoute = (_req: Request, res: Response) => res.send('Pong')

export default {
  pingHttpRoute
}
