import { PostsDatabase } from '../db/posts'
import { Request, Response } from 'express'
import { URL } from 'url'
import utils from '../utils/text'
import RSS, { EnclosureObject } from 'rss'
import logger from '../utils/logger'
import { HttpConfig } from './http'
import { FilesDatabase, Upload } from '../db/files'
import { NewPostRequest, NewPostResponse, UnvalidatedNewPostRequest } from '../models/posts'
import result, { Result } from '../models/result'
import { ApiError, writeErrorToResponse } from '../models/errors'

export class RssModule {
  constructor(
    public config: HttpConfig,
    private db: PostsDatabase,
    private files: FilesDatabase
  ) {}

  createPost = async (post: NewPostRequest): Promise<Result<NewPostResponse, ApiError>> => {
    try {
      const content = post.cleanupHtml ? utils.convertHtml(post.content) : post.content
      const tags = post.content.match(/(?<=^|\s)#\w+/gm)?.map((t) => t.substring(1)) || []
      const row = await this.db.createPost({
        content,
        link: post.link,
        language: post.language,
        tags,
        images: post.images
      })
      return result.success({
        module: 'rss',
        uri: new URL(`/rss/${row.uuid}`, this.config.baseUrl).toString()
      })
    } catch (e) {
      logger.error('Failed to save RSS item: ', e)
      return result.error({
        type: 'caught-exception',
        module: 'rss',
        error: 'Failed to save RSS item',
        status: 500
      })
    }
  }

  createPostRoute = async (
    post: UnvalidatedNewPostRequest
  ): Promise<Result<NewPostResponse, ApiError>> => {
    const content = post.content
    if (!content) {
      return result.error({
        type: 'validation-error',
        status: 400,
        error: 'Bad Request: Missing content!',
        module: 'rss'
      })
    }

    return await this.createPost({ ...post, content })
  }

  rss = async (options: {
    filterByLinks?: 'include' | 'exclude'
    filterByImages?: 'include' | 'exclude'
  }): Promise<string> => {
    const posts = await this.db.getPosts()
    const feed = new RSS({
      title: 'Feed of ' + this.config.baseUrl.replace(/^https?:\/\//, ''),
      feed_url: new URL('/rss', this.config.baseUrl).toString(),
      site_url: this.config.baseUrl,
      custom_namespaces: {
        media: 'http://search.yahoo.com/mrss/'
      }
    })
    for (const post of posts) {
      if (options.filterByLinks === 'include' && !post.link) {
        continue
      } else if (options.filterByLinks === 'exclude' && post.link) {
        continue
      }
      const hasImages = (post.images || []).length > 0
      if (options.filterByImages === 'include' && !hasImages) {
        continue
      } else if (options.filterByImages === 'exclude' && hasImages) {
        continue
      }

      const guid = new URL(`/rss/${post.uuid}`, this.config.baseUrl).toString()
      const mediaItems: unknown[] = []
      for (const uuid of post.images || []) {
        const file = await this.files.getFileByUuid(uuid)
        if (file)
          mediaItems.push({
            'media:content': [
              {
                _attr: {
                  url: `${this.config.baseUrl}/files/${file.uuid}`,
                  fileSize: file.size,
                  type: file.mimetype
                }
              },
              {
                'media:rating': {
                  _attar: { scheme: 'urn:simple' },
                  _cdata: 'nonadult'
                }
              },
              {
                'media:description': file.altText
                  ? {
                      _cdata: file.altText
                    }
                  : undefined
              }
            ]
          })
      }

      feed.item({
        title: post.content,
        description: post.content,
        categories: post.tags,
        guid,
        url: post.link || guid,
        date: post.createdAt,
        custom_elements: mediaItems.length > 0 ? mediaItems : undefined
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

    const filterByImages =
      req.query.filterByImages === 'include'
        ? 'include'
        : req.query.filterByImages === 'exclude'
          ? 'exclude'
          : undefined

    res.type('application/rss+xml')
    res.send(await this.rss({ filterByLinks, filterByImages }))
  }

  getRssItemHttpRoute = async (req: Request, res: Response) => {
    const uuid = req.params.uuid
    const post = await this.db.getPost(uuid)
    if (!post) {
      writeErrorToResponse(res, {
        type: 'validation-error',
        status: 404,
        error: 'Not Found: Post not found.',
        module: 'rss'
      })
      return
    }
    res.type('application/json')
    res.send(JSON.stringify(post, null, 2))
  }

  createPostHttpRoute = async (req: Request, res: Response) => {
    const r = await this.createPostRoute(req.body)
    if (r.type === 'success') {
      res.status(200).send(r.result)
    } else {
      writeErrorToResponse(res, r.error)
    }
  }
}
