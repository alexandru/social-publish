import express from 'express'
import blueskyApi from './bluesky-api'
import mastodonApi from './mastodon-api'
import rssApi from './rss-api'
import morgan from 'morgan'
import { PostHttpResponse } from './models'
import { Dictionary } from 'express-serve-static-core'
import { PostsDatabase } from './database'
import { jwtAuth, loginRoute } from './modules/authentication'

const app = express()
const port = 3000

// This will log requests to the console
app.use(morgan('combined'))
// This will parse application/x-www-form-urlencoded bodies
app.use(express.urlencoded({ extended: true }))
// This will parse application/json bodies
app.use(express.json())
// Serve static files
app.use('/app', express.static('public'))

app.get('/', (_req, res) => {
  res.redirect('/app/')
})

app.get('/rss', async (req, res) => {
  const filterByLinks =
    req.query.filterByLinks === "include" ? "include" :
    req.query.filterByLinks === "exclude" ? "exclude" :
    undefined

  res.type('application/rss+xml')
  res.send(await rssApi.rss({
    filterByLinks
  }))
})

app.get('/rss/:uuid', async (req, res) => {
  const uuid = req.params.uuid
  const post = await rssApi.getPost(uuid)
  if (!post) {
    res.status(404).send({ error: 'Not Found'})
    return
  }
  res.type('application/json')
  res.send(JSON.stringify(post, null, 2))
})

app.get('/ping', (_req, res) => {
  res.send('pong')
})

app.post('/api/login', loginRoute)

app.get('/api/protected', jwtAuth, (req, res) => {
  res.send({ username: req.user?.username })
})

app.post('/api/bluesky/post', jwtAuth, async (req, res) => {
  const { status, body } = await blueskyApi.createPostRoute(req.body)
  res.status(status).send(body)
})

app.post('/api/mastodon/post', jwtAuth, async (req, res) => {
  const { status, body } = await mastodonApi.createPostRoute(req.body)
  res.status(status).send(body)
})

app.post('/api/rss/post', jwtAuth, async (req, res) => {
  const { status, body } = await rssApi.createPostRoute(req.body)
  res.status(status).send(body)
})

app.post('/api/multiple/post', jwtAuth, async (req, res) => {
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

  if (req.body["rss"]) {
    published++
    const r = await rssApi.createPostRoute(req.body)
    if (r.status != 200) {
      errors["rss"] = r
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

// Initialize and migrate the database
PostsDatabase.init().catch((err) => {
  console.error(`[${new Date().toISOString()}] Failed to initialize database:`, err)
  process.exit(1)
})
