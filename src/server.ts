import express from 'express'
import basicAuth from 'express-basic-auth'
import blueskyApi from './bluesky-api'
import mastodonApi from './mastodon-api'
import morgan from 'morgan'

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
  if (!req.body["content"]) {
    res.status(400).send("Bad Request: Missing content!")
    return
  }
  try {
    const r = await blueskyApi.createPost({
      content: req.body["content"],
      langs: req.body["langs"] ? req.body["langs"].split(",") : undefined,
    })
    console.log(`Posted to Bluesky: ${r}`)
    res.send("OK")
  } catch (e) {
    console.error("While creating a Bluesky post", e)
    res.status(500).send("Internal Server Error (BlueSky)")
  }
})

app.post('/mastodon/post', auth, async (req, res) => {
  if (!req.body["content"]) {
    res.status(400).send("Bad Request: Missing content!")
    return
  }
  try {
    const url = await mastodonApi.createPost({
      content: req.body["content"],
    })
    console.log(`Posted to Mastodon: ${url}`)
    res.send("OK")
  } catch (e) {
    console.error("While creating a Mastodon post", e)
    res.status(500).send("Internal Server Error (Mastodon)")
  }
})

app.post('/multiple/post', auth, async (req, res) => {
  if (!req.body["content"]) {
    res.status(400).send("Bad Request: Missing content!")
    return
  }

  var hasError = false
  var published = 0

  if (req.body["mastodon"])
    try {
      const url = await mastodonApi.createPost({
        content: req.body["content"],
      })
      console.log(`Posted to Mastodon: ${url}`)
      published++
    } catch (e) {
      console.error("While creating a Mastodon post", e)
      hasError = true
    }

  if (req.body["bluesky"])
    try {
      const r = await blueskyApi.createPost({
        content: req.body["content"],
        langs: req.body["langs"] ? req.body["langs"].split(",") : undefined,
      })
      console.log(`Posted to Bluesky: ${r}`)
      published++
    } catch (e) {
      console.error("While creating a Bluesky post", e)
      hasError = true
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
  console.log(`Server is running at http://localhost:${port}`)
})
