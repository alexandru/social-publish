import qs from 'qs'
import utils from '../utils/text'
import { Request, Response } from 'express'
import logger from '../utils/logger'
import { FilesModule } from './files'
import result, { Result } from '../models/result'
import { sleep } from '../utils/proc'
import {
  ApiError,
  buildErrorFromResponse,
  extractStatusFrom,
  writeErrorToResponse
} from '../models/errors'
import { CreatePostFunction, NewPostRequestSchema, NewPostResponse } from '../models/posts'

export type MastodonApiConfig = {
  mastodonHost: string
  mastodonAccessToken: string
}

export type MastodonMediaUploadResponse = {
  id: string
  url: string
  previewUrl: string
  description: string
}

export class MastodonApiModule {
  constructor(
    public config: MastodonApiConfig,
    private files: FilesModule
  ) {}

  private mediaUrlV1 = `${this.config.mastodonHost}/api/v1/media`
  private mediaUrlV2 = `${this.config.mastodonHost}/api/v2/media`
  private statusesUrlV1 = `${this.config.mastodonHost}/api/v1/statuses`

  private uploadMedia = async (
    uuid: string
  ): Promise<Result<MastodonMediaUploadResponse, ApiError>> => {
    try {
      const r = await this.files.readImageFile(uuid)
      if (r === null)
        return result.error({
          type: 'validation-error',
          module: 'mastodon',
          status: 404,
          error: `Failed to read image file — uuid: ${uuid}`
        })

      const formData = new FormData()
      formData.append('file', new Blob([r.bytes], { type: r.mimetype }), r.originalname)
      if (r.altText) formData.append('description', r.altText)

      const response = await fetch(this.mediaUrlV2, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${this.config.mastodonAccessToken}`
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
          const response = await fetch(`${this.mediaUrlV1}/${id}`, {
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
            return result.error(await buildErrorFromResponse(response, 'mastodon', { uuid }))
          }
        }
      } else {
        return result.error(await buildErrorFromResponse(response, 'mastodon', { uuid }))
      }
    } catch (err) {
      logger.error(`Failed to upload media (mastodon) — uuid ${uuid}:`, err)
      return result.error({
        type: 'caught-exception',
        error: `Failed to upload media — uuid: ${uuid}`,
        status: 500,
        module: 'mastodon'
      })
    }
  }

  createPost: CreatePostFunction = async (post) => {
    try {
      const images: string[] = []
      if (post.images) {
        const rs = await Promise.all(post.images.map(this.uploadMedia))
        logger.info('Uploaded images:', rs)

        for (const r of rs) {
          if (r.type === 'success') {
            images.push(r.result.id)
          } else {
            return result.error({
              type: 'composite-error',
              status: extractStatusFrom(...rs),
              module: 'mastodon',
              error: 'Failed to upload images.',
              responses: rs
            })
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
        return result.error(await buildErrorFromResponse(response, 'mastodon'))
      }
      const body = await response.json()
      return result.success({
        module: 'mastodon',
        uri: body['url']
      })
    } catch (e) {
      logger.error('Failed to post to Mastodon:', e)
      return result.error({
        type: 'caught-exception',
        module: 'mastodon',
        error: e
      })
    }
  }

  createPostRoute = async (body: unknown): Promise<Result<NewPostResponse, ApiError>> => {
    const parsed = NewPostRequestSchema.safeParse(body)
    if (parsed.success === false) {
      return result.error({
        type: 'validation-error',
        status: 400,
        error: `Bad Request: ${parsed.error.format()._errors.join(', ')}`,
        module: 'mastodon'
      })
    }
    return await this.createPost(parsed.data)
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
