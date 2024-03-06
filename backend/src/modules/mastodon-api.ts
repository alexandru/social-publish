import axios from 'axios'
import qs from 'qs'
import {
  PostError,
  PostHttpResponse,
  PostRequest,
  PostResponse,
  UnvalidatedPostRequest
} from '../models'
import utils from '../utils/text'
import { Request, Response } from 'express'
import logger from '../utils/logger'

export type MastodonApiConfig = {
  mastodonHost: string
  mastodonAccessToken: string
}

export class MastodonApiModule {
  constructor(public config: MastodonApiConfig) {}

  createPost = async (post: PostRequest): Promise<PostResponse | PostError> => {
    try {
      const url = `${this.config.mastodonHost}/api/v1/statuses`
      const status =
        (post.cleanupHtml ? utils.convertHtml(post.content) : post.content.trim()) +
        (post.link ? `\n\n${post.link}` : '')
      const data = {
        status,
        language: post.language
      }
      const response = await axios.post(url, qs.stringify(data), {
        headers: {
          Authorization: `Bearer ${this.config.mastodonAccessToken}`,
          'Content-Type': 'application/x-www-form-urlencoded'
        }
      })
      if (response.status >= 400) {
        logger.error('Failed to post to Mastodon: ', response)
        return {
          isSuccessful: false,
          status: response.status,
          error: response.data['error'] || 'HTTP ' + response.status
        }
      } else {
        return {
          isSuccessful: true,
          uri: response.data['url']
        }
      }
    } catch (e) {
      logger.error('Failed to post to Mastodon:', e)
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
    logger.info('Posted to Mastodon: ', r)
    if (!r.isSuccessful) {
      return r.status
        ? { status: r.status, body: '' + r.error }
        : { status: 500, body: 'Internal Server Error (BlueSky)' }
    } else {
      return { status: 200, body: 'OK' }
    }
  }

  createPostHttpRoute = async (req: Request, res: Response) => {
    const { status, body } = await this.createPostRoute(req.body)
    res.status(status).send(body)
  }
}
