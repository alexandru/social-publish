import express from 'express'
import basicAuth from 'express-basic-auth'
import blueskyApi from './bluesky-api'
import mastodonApi from './mastodon-api'
import morgan from 'morgan'
import { stat } from 'fs'

const app = express()
const port = 3000

const auth = (() => {
  const user = process.env.SERVER_AUTH_USERNAME
  if (!user) throw new Error('SERVER_AUTH_USERNAME is not set')
  const pass = process.env.SERVER_AUTH_PASSWORD
  if (!pass) throw new Error('SERVER_AUTH_PASSWORD is not set')
  return basicAuth({
    users: { [user]: pass },
    challenge: true,
  })
})()

// This will log requests to the console
app.use(morgan('combined'))
// This will parse application/x-www-form-urlencoded bodies
app.use(express.urlencoded({ extended: true }))

app.get('/', (req, res) => {
  res.send('Hello World!')
})

app.post('/bluesky/post', auth, async (req, res) => {
  const { status, body } = await blueskyApi.createPostRoute(req.body)
  res.status(status).send(body)
})

app.post('/mastodon/post', auth, async (req, res) => {
  const { status, body } = await mastodonApi.createPostRoute(req.body)
  res.status(status).send(body)
})

app.post('/multiple/post', auth, async (req, res) => {
  if (!req.body["content"]) {
    res.status(400).send("Bad Request: Missing content!")
    return
  }

  var hasError = false
  var published = 0

  if (req.body["mastodon"]) {
    published++
    const { status, body } = await mastodonApi.createPostRoute(req.body)
    if (status != 200) {
      hasError = true
    }
  }

  if (req.body["bluesky"]) {
    published++
    const { status, body } = await blueskyApi.createPostRoute(req.body)
    if (status != 200) {
      hasError = true
    }
  }

  if (hasError) {
    res.status(500).send("Internal Server Error")
  } else if (published == 0) {
    res.status(400).send("Bad Request: No service specified!")
  } else {
    res.send("OK")
  }
})

app.listen(port, () => {
  console.log(`[${new Date().toISOString()}] Server is running at http://localhost:${port}`)
})
