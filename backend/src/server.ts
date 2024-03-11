import express from 'express'
import healthApi from './modules/health'
import logger from './utils/logger'
import morgan from 'morgan'
import { AuthModule } from './modules/authentication'
import { RssModule } from './modules/rss-api'
import { MastodonApiModule } from './modules/mastodon-api'
import { BlueskyApiModule } from './modules/bluesky-api'
import { FormModule } from './modules/form'
import { FilesModule } from './modules/files'
import { HttpConfig } from './modules/http'
import { parseCookiesMiddleware } from './modules/utils'
import { TwitterApiModule } from './modules/twitter-api'

export const startServer = async (
  config: HttpConfig,
  auth: AuthModule,
  rss: RssModule,
  mastodon: MastodonApiModule,
  bluesky: BlueskyApiModule,
  twitter: TwitterApiModule,
  form: FormModule,
  files: FilesModule
) => {
  const app = express()

  // This will log requests to the console
  app.use(morgan('combined'))
  // This will parse application/x-www-form-urlencoded bodies
  app.use(express.urlencoded({ extended: true }))
  // This will parse application/json bodies
  app.use(express.json())
  // Parses cookies (custom implementation)
  app.use(parseCookiesMiddleware)
  // Serve static files
  app.use('/', express.static('public'))

  // Health checks
  app.get('/ping', healthApi.pingHttpRoute)
  app.get('/api/protected', auth.middleware, auth.protectedHttpRoute)

  // RSS export
  app.get('/rss', rss.generateRssHttpRoute)
  app.get('/rss/:uuid', rss.getRssItemHttpRoute)
  // Other static content
  app.get('/files/:uuid', files.getUploadedFileRoute)

  // Authentication
  app.post('/api/login', auth.loginHttpRoute)

  // Twitter routes
  app.get('/api/twitter/authorize', auth.middleware, twitter.authorizeHttpRoute)
  app.get('/api/twitter/callback', auth.middleware, twitter.authCallbackHttpRoute)
  app.get('/api/twitter/status', auth.middleware, twitter.statusHttpRoute)
  app.post('/api/twitter/post', auth.middleware, twitter.createPostHttpRoute)

  // Publishing routes
  app.post('/api/bluesky/post', auth.middleware, bluesky.createPostHttpRoute)
  app.post('/api/mastodon/post', auth.middleware, mastodon.createPostHttpRoute)
  app.post('/api/rss/post', auth.middleware, rss.createPostHttpRoute)
  app.post('/api/multiple/post', auth.middleware, form.broadcastPostToManyHttpRoute)
  app.post('/api/files/upload', auth.middleware, files.middleware, files.uploadFilesHttpRoute)

  // Needed for the frontend routing
  app.get(/\/(login|form|account)/, (_req, res) => {
    res.sendFile('public/index.html', { root: `${__dirname}/..` })
  })

  return app.listen(config.httpPort, () => {
    logger.info(`Server is running at: http://localhost:${config.httpPort}`)
  })
}
