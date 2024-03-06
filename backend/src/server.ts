import express, { Express } from 'express'
import healthApi from './modules/health'
import morgan from 'morgan'
import logger from './utils/logger'
import { AuthModule } from './modules/authentication'
import { RssModule } from './modules/rss-api'
import { MastodonApiModule } from './modules/mastodon-api'
import { BlueskyApiModule } from './modules/bluesky-api'
import { FormModule } from './modules/form'

export type HttpConfig = {
  httpPort: number
}

export const startServer = async (
  config: HttpConfig,
  auth: AuthModule,
  rss: RssModule,
  mastodon: MastodonApiModule,
  bluesky: BlueskyApiModule,
  form: FormModule
) => {
  const app: Express = express()

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
  app.get('/api/protected', auth.middleware, auth.protectedHttpRoute)

  // RSS export
  app.get('/rss', rss.generateRssHttpRoute)
  app.get('/rss/:uuid', rss.getRssItemHttpRoute)

  // Authentication
  app.post('/api/login', auth.loginHttpRoute)

  // Publishing routes
  app.post('/api/bluesky/post', auth.middleware, bluesky.createPostHttpRoute)
  app.post('/api/mastodon/post', auth.middleware, mastodon.createPostHttpRoute)
  app.post('/api/rss/post', auth.middleware, rss.createPostHttpRoute)
  app.post('/api/multiple/post', auth.middleware, form.broadcastPostToManyHttpRoute)

  // Needed for the frontend routing
  app.get(/\/(login|form)/, (_req, res) => {
    res.sendFile('public/index.html', { root: __dirname + '/..' })
  })

  return app.listen(config.httpPort, () => {
    logger.info(`Server is running at: http://localhost:${config.httpPort}`)
  })
}
