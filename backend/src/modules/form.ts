import { Request, Response } from 'express'
import { MastodonApiModule } from './mastodon-api'
import { BlueskyApiModule } from './bluesky-api'
import { RssModule } from './rss-api'
import { writeErrorToResponse } from '../models/errors'
import { Dictionary } from '../models/base'
import { NewPostResponse } from '../models/posts'

export class FormModule {
  private modules: ('mastodon' | 'bluesky' | 'rss')[] =
    ['mastodon', 'bluesky', 'rss']

  constructor(
    public mastodonApi: MastodonApiModule,
    public blueskyApi: BlueskyApiModule,
    public rssApi: RssModule
  ) {}

  broadcastPostToManyHttpRoute = async (req: Request, res: Response) => {
    if (!req.body['content']) {
      writeErrorToResponse(res, {
        type: 'validation-error',
        status: 400,
        module: 'form',
        error: 'No content provided.'
      })
      return
    }

    const responses = await Promise.all(
      this.modules
        .filter((it) => !!req.body[it])
        .map((module) =>
          (async () => {
            switch (module) {
              case 'mastodon':
                return await this.mastodonApi.createPostRoute(req.body)
              case 'bluesky':
                return await this.blueskyApi.createPostRoute(req.body)
              case 'rss':
                return await this.rssApi.createPostRoute(req.body)
            }
          })()
        )
    )

    const filteredErrors = responses.flatMap((r) => {
      if (r.type === 'error') {
        return [r.error]
      }
      return []
    })
    if (filteredErrors.length > 0) {
      const modules = filteredErrors.map((e) => e.module).join(', ')
      return writeErrorToResponse(res, {
        type: 'composite-error',
        status: 502,
        error: `Failed to create post via ${modules}.`,
        module: 'form',
        responses: responses
      })
    }

    const replies = responses.flatMap(r => r.type === 'success' ? [r.result] : [])
    const asMap: Dictionary<NewPostResponse> = {}
    for (const reply of replies) {
      asMap[reply.module] = reply
    }
    res.status(200).send(asMap)
  }
}
