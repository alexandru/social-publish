export type UnvalidatedNewPostRequest = {
  content?: string
  link?: string
  language?: string
  cleanupHtml?: boolean
  images?: string[]
}

export type NewPostRequest = UnvalidatedNewPostRequest & {
  content: string
}

export type NewPostResponse = NewMastodonPostResponse | NewBlueSkyPostResponse | NewRssPostResponse

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
