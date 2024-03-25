import { z } from 'zod'
import { Result } from './result'
import { ApiError } from './errors'

export type Target = 'mastodon' | 'bluesky' | 'twitter' | 'linkedin'

export const NewPostRequest = z.object({
  content: z.string().min(1).max(1000),
  targets: z.array(z.enum(['mastodon', 'bluesky', 'twitter', 'linkedin'])).optional(),
  link: z.string().optional(),
  language: z.string().optional(),
  cleanupHtml: z.boolean().optional(),
  images: z.array(z.string()).optional()
})

export type NewPostRequest = z.infer<typeof NewPostRequest>

export type NewPostResponse =
  | NewMastodonPostResponse
  | NewBlueSkyPostResponse
  | NewTwitterPostResponse
  | NewRssPostResponse

export type NewBlueSkyPostResponse = {
  module: 'bluesky'
  uri: string
  cid?: string
}

export type NewMastodonPostResponse = {
  module: 'mastodon'
  uri: string
}

export type NewRssPostResponse = {
  module: 'rss'
  uri: string
}

export type NewTwitterPostResponse = {
  module: 'twitter'
  id: string
}

export type CreatePostFunction = (
  post: NewPostRequest
) => Promise<Result<NewPostResponse, ApiError>>
