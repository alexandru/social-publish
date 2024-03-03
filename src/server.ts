import express from 'express'
import basicAuth from 'express-basic-auth'
import blueskyApi from './bluesky-api'
import mastodonApi from './mastodon-api'
import morgan from 'morgan'
import { PostHttpResponse } from './models'
import { Dictionary } from 'express-serve-static-core'

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
// Serve static files
app.use('/app', auth, express.static('public'))
// Protect the API with basic auth
app.use('/api', auth)

app.get('/', (_req, res) => {
  res.redirect('/app/')
})

app.get('/ping', (_req, res) => {
  res.send('pong')
})

app.post('/api/bluesky/post', async (req, res) => {
  const { status, body } = await blueskyApi.createPostRoute(req.body)
  res.status(status).send(body)
})

app.post('/api/mastodon/post', async (req, res) => {
  const { status, body } = await mastodonApi.createPostRoute(req.body)
  res.status(status).send(body)
})

app.post('/api/multiple/post', async (req, res) => {
  if (!req.body["content"]) {
    res.status(400).send("Bad Request: Missing content!")
    return
  }

  var errors: Dictionary<PostHttpResponse> = {}
  var published = 0

  if (req.body["mastodon"]) {
    published++
    const r = await mastodonApi.createPostRoute(req.body)
    if (r.status != 200) {
      errors["mastodon"] = r
    }
  }

  if (req.body["bluesky"]) {
    published++
    const r = await blueskyApi.createPostRoute(req.body)
    if (r.status != 200) {
      errors["bluesky"] = r
    }
  }

  if (Object.keys(errors).length > 0) {
    let maxStatus = 0
    for (const value of Object.values(errors)) {
      if (value.status > maxStatus) {
        maxStatus = value.status
      }
    }
    res.status(maxStatus).send(errors)
  } else if (published == 0) {
    res.status(400).send("Bad Request: No service specified!")
  } else {
    res.send("OK")
  }
})

app.listen(port, () => {
  console.log(`[${new Date().toISOString()}] Server is running at http://localhost:${port}`)
})
