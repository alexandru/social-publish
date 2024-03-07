import { Request, Response } from 'express'
import { Dictionary, PostHttpResponse } from '../models'
import { MastodonApiModule } from './mastodon-api'
import { BlueskyApiModule } from './bluesky-api'
import { RssModule } from './rss-api'

export class FormModule {
  constructor(
    public mastodonApi: MastodonApiModule,
    public blueskyApi: BlueskyApiModule,
    public rssApi: RssModule
  ) {}

  broadcastPostToManyHttpRoute = async (req: Request, res: Response) => {
    if (!req.body['content']) {
      res.status(400).send('Bad Request: Missing content!')
      return
    }

    var errors: Dictionary<PostHttpResponse> = {}
    var published = 0

    if (req.body['mastodon']) {
      published++
      const r = await this.mastodonApi.createPostRoute(req.body)
      if (r.status != 200) {
        errors['mastodon'] = r
      }
    }

    if (req.body['bluesky']) {
      published++
      const r = await this.blueskyApi.createPostRoute(req.body)
      if (r.status != 200) {
        errors['bluesky'] = r
      }
    }

    if (req.body['rss']) {
      published++
      const r = await this.rssApi.createPostRoute(req.body)
      if (r.status != 200) {
        errors['rss'] = r
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
      res.status(400).send('Bad Request: No service specified!')
    } else {
      res.send('OK')
    }
  }
}
