import { PostsDatabase } from '../db/posts'
import {
  PostError,
  PostHttpResponse,
  PostRequest,
  PostResponse,
  UnvalidatedPostRequest
} from '../models'

import { Request, Response } from 'express'
import { URL } from 'url'
import utils from '../utils/text'
import RSS from 'rss'
import logger from '../utils/logger'
import { HttpConfig } from './http'

export class RssModule {
  constructor(
    public config: HttpConfig,
    private db: PostsDatabase,
  ) {}

  createPost = async (post: PostRequest): Promise<PostResponse | PostError> => {
    try {
      const content = post.cleanupHtml ? utils.convertHtml(post.content) : post.content
      const tags = post.content.match(/(?<=^|\s)#\w+/gm)?.map((t) => t.substring(1)) || []
      const row = await this.db.createPost({
        content,
        link: post.link,
        language: post.language,
        tags
      })
      return {
        isSuccessful: true,
        uri: new URL(`/rss/${row.uuid}`, this.config.baseUrl).toString()
      }
    } catch (e) {
      logger.error('Failed to save RSS item: ', e)
      return {
        isSuccessful: false,
        error: e
      }
    }
  }

  createPostRoute = async (post: UnvalidatedPostRequest): Promise<PostHttpResponse> => {
    const content = post.content
    if (!content) {
      return { status: 400, body: 'Bad Request: Missing content!' }
    }
    const r = await this.createPost({ ...post, content })
    logger.info('Saved RSS item: ', r)
    if (!r.isSuccessful) {
      return r.status
        ? { status: r.status, body: '' + r.error }
        : { status: 500, body: 'Internal Server Error (RSS)' }
    } else {
      return { status: 200, body: 'OK' }
    }
  }

  rss = async (options: { filterByLinks?: 'include' | 'exclude' }): Promise<string> => {
    const posts = await this.db.getPosts()
    const feed = new RSS({
      title: 'Feed of ' + this.config.baseUrl.replace(/^https?:\/\//, ''),
      feed_url: new URL('/rss', this.config.baseUrl).toString(),
      site_url: this.config.baseUrl
    })
    for (const post of posts) {
      if (options.filterByLinks === 'include' && !post.link) {
        continue
      } else if (options.filterByLinks === 'exclude' && post.link) {
        continue
      }
      const guid = new URL(`/rss/${post.uuid}`, this.config.baseUrl).toString()
      feed.item({
        title: post.content,
        description: post.content,
        categories: post.tags,
        guid,
        url: post.link || guid,
        date: post.createdAt
      })
    }
    return feed.xml({ indent: true })
  }

  generateRssHttpRoute = async (req: Request, res: Response) => {
    const filterByLinks =
      req.query.filterByLinks === 'include'
        ? 'include'
        : req.query.filterByLinks === 'exclude'
          ? 'exclude'
          : undefined

    res.type('application/rss+xml')
    res.send(await this.rss({ filterByLinks }))
  }

  getRssItemHttpRoute = async (req: Request, res: Response) => {
    const uuid = req.params.uuid
    const post = await this.db.getPost(uuid)
    if (!post) {
      res.status(404).send({ error: 'Not Found' })
      return
    }
    res.type('application/json')
    res.send(JSON.stringify(post, null, 2))
  }

  createPostHttpRoute = async (req: Request, res: Response) => {
    const { status, body } = await this.createPostRoute(req.body)
    res.status(status).send(body)
  }
}
