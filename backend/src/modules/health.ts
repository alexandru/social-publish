import { Request, Response } from "express"

const pingHttpRoute = (req: Request, res: Response) =>
  res.send("Pong")

export default {
  pingHttpRoute,
}
