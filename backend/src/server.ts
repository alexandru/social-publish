import express from 'express'
import blueskyApi from './modules/bluesky-api'
import mastodonApi from './modules/mastodon-api'
import rssApi from './modules/rss-api'
import healthApi from './modules/health'
import morgan from 'morgan'
import { Dictionary, PostHttpResponse } from './models'
import { PostsDatabase } from './database'
import authApi, { jwtAuth } from './modules/authentication'
import form from './modules/form'

const app = express()
const port = 3000

// This will log requests to the console
app.use(morgan('combined'))
// This will parse application/x-www-form-urlencoded bodies
app.use(express.urlencoded({ extended: true }))
// This will parse application/json bodies
app.use(express.json())
// Serve static files
app.use('/', express.static('public'))

// Health checks
app.get('/ping', healthApi.pingHttpRoute)
app.get('/api/protected', jwtAuth, authApi.protectedHttpRoute)

// RSS export
app.get('/rss', rssApi.generateRssHttpRoute)
app.get('/rss/:uuid', rssApi.getRssItemHttpRoute)

// Authentication
app.post('/api/login', authApi.loginRoute)

// Publishing routes
app.post('/api/bluesky/post', jwtAuth, blueskyApi.createPostHttpRoute)
app.post('/api/mastodon/post', jwtAuth, mastodonApi.createPostHttpRoute)
app.post('/api/rss/post', jwtAuth, rssApi.createPostHttpRoute)
app.post('/api/multiple/post', jwtAuth, form.broadcastPostToManyHttpRoute)

// Needed for the frontend routing
app.get(/\/(login|form)/, (_req, res) => {
  res.sendFile('public/index.html', { root: __dirname + '/..' })
})

app.listen(port, () => {
  console.log(`[${new Date().toISOString()}] Server is running at http://localhost:${port}`)
})
