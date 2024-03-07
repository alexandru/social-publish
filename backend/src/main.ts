import { DBConfig, withBaseConnection } from './db/base'
import { PostsDatabase } from './db/posts'
import { AuthConfig, AuthModule } from './modules/authentication'
import { BlueskyApiConfig, BlueskyApiModule } from './modules/bluesky-api'
import { FormModule } from './modules/form'
import { MastodonApiConfig, MastodonApiModule } from './modules/mastodon-api'
import { RssModule } from './modules/rss-api'
import { startServer } from './server'
import logger from './utils/logger'
import yargs from 'yargs'
import { waitOnTerminationSignal } from './utils/proc'
import { FilesDatabase } from './db/files'
import { FilesConfig, FilesModule } from './modules/files'
import { HttpConfig } from './modules/http'

type AppConfig = DBConfig &
  HttpConfig &
  AuthConfig &
  BlueskyApiConfig &
  MastodonApiConfig &
  FilesConfig

async function main() {
  const args: AppConfig = await yargs
    .option('dbPath', {
      type: 'string',
      description: 'Path to the SQLite database file',
      demandOption: true,
      default: process.env.DB_PATH,
      defaultDescription: 'DB_PATH env variable'
    })
    .option('httpPort', {
      type: 'number',
      description: 'Port to listen on',
      demandOption: true,
      default: process.env.HTTP_PORT ? parseInt(process.env.HTTP_PORT) : 3000,
      defaultDescription: 'HTTP_PORT env || 3000'
    })
    .option('baseUrl', {
      type: 'string',
      description: 'Public URL of this server',
      demandOption: true,
      default: process.env.BASE_URL,
      defaultDescription: 'BASE_URL env variable'
    })
    .option('serverAuthUsername', {
      type: 'string',
      description: 'Your username for this server',
      demandOption: true,
      default: process.env.SERVER_AUTH_USERNAME,
      defaultDescription: 'SERVER_AUTH_USERNAME env variable'
    })
    .option('serverAuthPassword', {
      type: 'string',
      description: 'Your password for this server',
      demandOption: true,
      default: process.env.SERVER_AUTH_PASSWORD,
      defaultDescription: 'SERVER_AUTH_PASSWORD env variable'
    })
    .option('serverAuthJwtSecret', {
      type: 'string',
      description: 'JWT secret for this server`s authentication',
      demandOption: true,
      default: process.env.JWT_SECRET,
      defaultDescription: 'JWT_SECRET env variable'
    })
    .option('blueskyService', {
      type: 'string',
      description: 'URL of the BlueSky service',
      demandOption: true,
      default: process.env.BSKY_SERVICE || 'https://bsky.social',
      defaultDescription: 'BSKY_SERVICE env || "https://bsky.social"'
    })
    .option('blueskyUsername', {
      type: 'string',
      description: 'Username for the Bluesky authentication',
      demandOption: true,
      default: process.env.BSKY_USERNAME,
      defaultDescription: 'BSKY_USERNAME env variable'
    })
    .option('blueskyPassword', {
      type: 'string',
      description: 'Password for the Bluesky authentication',
      demandOption: true,
      default: process.env.BSKY_PASSWORD,
      defaultDescription: 'BSKY_PASSWORD env variable'
    })
    .option('mastodonHost', {
      type: 'string',
      description: 'Host of the Mastodon service',
      demandOption: true,
      default: process.env.MASTODON_HOST,
      defaultDescription: 'MASTODON_HOST env variable'
    })
    .option('mastodonAccessToken', {
      type: 'string',
      description: 'Access token for the Mastodon service',
      demandOption: true,
      default: process.env.MASTODON_ACCESS_TOKEN,
      defaultDescription: 'MASTODON_ACCESS_TOKEN env variable'
    })
    .option('uploadedFilesPath', {
      type: 'string',
      description: 'Directory where uploaded files are stored and processed',
      demandOption: true,
      default: process.env.UPLOADED_FILES_PATH,
      defaultDescription: 'UPLOADED_FILES_PATH env variable'
    })
    .help()
    .alias('help', 'h').argv

  withBaseConnection(args)(async (dbConn) => {
    logger.info('Connected to database')

    const postsDb = await PostsDatabase.init(dbConn)
    const filesDb = await FilesDatabase.init(dbConn)
    const auth = new AuthModule(args)
    const files = await FilesModule.init(args, filesDb)
    const rss = new RssModule(args, postsDb, filesDb)
    const mastodon = new MastodonApiModule(args, files)
    const bluesky = await BlueskyApiModule.create(args, files)
    const form = new FormModule(mastodon, bluesky, rss)

    const server = await startServer(args, auth, rss, mastodon, bluesky, form, files)
    try {
      const signal = await waitOnTerminationSignal()
      logger.info(`Received ${signal}, shutting down...`)
    } finally {
      server.close()
    }
  })
}

main().catch((err) => {
  logger.error('Uncaught exception in main:', err)
  process.exit(1)
})
