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
import { FilesModule } from './files'
import result from '../utils/result'
import { sleep } from '../utils/proc'

export type MastodonApiConfig = {
  mastodonHost: string
  mastodonAccessToken: string
}

export class MastodonApiModule {
  constructor(
    public config: MastodonApiConfig,
    private files: FilesModule
  ) {}

  private mediaUrlV1 = `${this.config.mastodonHost}/api/v1/media`
  private mediaUrlV2 = `${this.config.mastodonHost}/api/v2/media`
  private statusesUrlV1 = `${this.config.mastodonHost}/api/v1/statuses`

  private uploadMedia = async (uuid: string) => {
    const r = await this.files.readImageFile(uuid)
    if (r === null)
      return result.error({
        status: 404,
        error: 'Failed to read image file — uuid: ' + uuid
      })

    const formData = new FormData()
    formData.append(
      'file',
      new Blob([r.bytes], { type: r.mimetype }),
      r.originalname
    )
    if (r.altText) formData.append('description', r.altText)

    const response = await fetch(this.mediaUrlV2, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${this.config.mastodonAccessToken}`,
      },
      body: formData
    })

    if (response.status == 200) {
      const data = await response.json()
      return result.success({
        id: data['id'] as string,
        url: data['url'] as string,
        previewUrl: data['preview_url'] as string,
        description: data['description'] as string
      })
    } else if (response.status == 202) {
      const id = (await response.json())['id'] as string
      while (true) {
        sleep(200)
        const response = await fetch(this.mediaUrlV1 + '/' + id, {
          method: 'GET',
          headers: {
            Authorization: `Bearer ${this.config.mastodonAccessToken}`
          }
        })
        if (response.status == 200) {
          const data = await response.json()
          return result.success({
            id: data['id'] as string,
            url: data['url'] as string,
            previewUrl: data['preview_url'] as string,
            description: data['description'] as string
          })
        } else if (response.status != 202) {
          logger.warn('Failed to upload media:', {
            uuid,
            status: response.status,
            body: await response.text()
          })
          return result.error({
            status: response.status,
            error: `Failed to upload media`
          })
        }
      }
    } else {
      logger.warn('Failed to upload media:', {
        uuid,
        status: response.status,
        body: await response.text()
      })
      return result.error({
        status: response.status,
        error: `Failed to upload media — uuid: ${uuid}`
      })
    }
  }

  createPost = async (post: PostRequest): Promise<PostResponse | PostError> => {
    try {
      const images: string[] = []
      if (post.images) {
        const rs = (await Promise.all(post.images.map(this.uploadMedia)))
        logger.info('Uploaded images:', rs)

        for (const result of rs) {
          if (result.type === 'success') {
            images.push(result.result.id)
          } else {
            return {
              isSuccessful: false,
              ...result.error
            }
          }
        }
      }

      const status =
        (post.cleanupHtml ? utils.convertHtml(post.content) : post.content.trim()) +
        (post.link ? `\n\n${post.link}` : '')

      const data = {
        status,
        language: post.language,
        media_ids: images.length > 0 ? images : undefined
      }

      const encoded = qs.stringify(data, { arrayFormat: 'brackets' })
      logger.debug('Posting to Mastodon:', encoded)
      const response = await fetch(this.statusesUrlV1, {
        headers: {
          Authorization: `Bearer ${this.config.mastodonAccessToken}`,
          'Content-Type': 'application/x-www-form-urlencoded'
        },
        method: 'POST',
        body: encoded
      })

      if (response.status >= 400) {
        logger.error('Failed to post to Mastodon: ', response)
        const data = await response.json()
        return {
          isSuccessful: false,
          status: response.status,
          error: data['error'] || 'HTTP ' + response.status
        }
      } else {
        const body = await response.json()
        return {
          isSuccessful: true,
          uri: body['url']
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
