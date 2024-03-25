import { Request, Response } from 'express'
import { MastodonApiModule } from './mastodon-api'
import { BlueskyApiModule } from './bluesky-api'
import { RssModule } from './rss-api'
import { extractStatusFrom, writeErrorToResponse } from '../models/errors'
import { Dictionary } from '../models/base'
import { CreatePostFunction, NewPostRequestSchema, NewPostResponse } from '../models/posts'
import { TwitterApiModule } from './twitter-api'

export class FormModule {
  constructor(
    public mastodonApi: MastodonApiModule,
    public blueskyApi: BlueskyApiModule,
    public twitterApi: TwitterApiModule,
    public rssApi: RssModule
  ) {}

  broadcastPostToManyHttpRoute = async (req: Request, res: Response) => {
    const parsed = NewPostRequestSchema.safeParse(req.body)
    if (parsed.success === false) {
      return writeErrorToResponse(res, {
        type: 'validation-error',
        status: 400,
        error: `Bad Request: ${parsed.error.format()._errors.join(', ')}`,
        module: 'form'
      })
    }
    const post = parsed.data
    const modules: CreatePostFunction[] = [this.rssApi.createPost]
    if ((post?.targets || []).includes('mastodon')) {
      modules.push(this.mastodonApi.createPost)
    }
    if ((post?.targets || []).includes('bluesky')) {
      modules.push(this.blueskyApi.createPost)
    }
    if ((post?.targets || []).includes('twitter')) {
      modules.push(this.twitterApi.createPost)
    }

    const responses = await Promise.all(modules.map((m) => m(post)))
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
        status: extractStatusFrom(...responses),
        error: `Failed to create post via ${modules}.`,
        module: 'form',
        responses
      })
    }

    const replies = responses.flatMap((r) => (r.type === 'success' ? [r.result] : []))
    const asMap: Dictionary<NewPostResponse> = {}
    for (const reply of replies) {
      asMap[reply.module] = reply
    }
    res.status(200).send(asMap)
  }
}
