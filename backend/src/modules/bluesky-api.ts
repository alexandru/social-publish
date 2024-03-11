import { BlobRef, BskyAgent, RichText } from '@atproto/api'
import utils from '../utils/text'
import { Request, Response } from 'express'
import logger from '../utils/logger'
import { FilesModule } from './files'
import { NewPostRequest, NewPostResponse, UnvalidatedNewPostRequest } from '../models/posts'
import result, { Result } from '../models/result'
import { ApiError, extractStatusFrom, writeErrorToResponse } from '../models/errors'

export type BlueskyApiConfig = {
  blueskyService: string
  blueskyUsername: string
  blueskyPassword: string
}

export type BlueskyMediaUploadResponse = {
  image: BlobRef
  alt?: string
  aspectRatio?: {
    width: number
    height: number
  }
}

export class BlueskyApiModule {
  constructor(
    public config: BlueskyApiConfig,
    private agent: BskyAgent,
    private files: FilesModule
  ) {}

  static create = async (
    config: BlueskyApiConfig,
    files: FilesModule
  ): Promise<BlueskyApiModule> => {
    const agent = new BskyAgent({
      service: config.blueskyService
    })
    await agent.login({
      identifier: config.blueskyUsername,
      password: config.blueskyPassword
    })
    return new BlueskyApiModule(config, agent, files)
  }

  private responseErrorToApiError = (message: string, e: any): ApiError => {
    logger.error(message, e)
    const eany = e as any
    if (eany.status && eany.error)
      return {
        type: 'request-error',
        module: 'bluesky',
        status: eany.status,
        error: message,
        body: {
          asString: eany.error
        }
      }
    return {
      type: 'caught-exception',
      module: 'bluesky',
      error: e
    }
  }

  createPost = async (post: NewPostRequest): Promise<Result<NewPostResponse, ApiError>> => {
    try {
      const imageUploadsResults: Result<BlueskyMediaUploadResponse, ApiError>[] = await Promise.all(
        (post.images || []).map((imageUuid) =>
          (async () => {
            try {
              const r = await this.files.readImageFile(imageUuid)
              if (!r)
                return result.error({
                  type: 'validation-error',
                  status: 404,
                  error: `Image not found: ${imageUuid}`,
                  module: 'bluesky'
                })

              const blob = await this.agent.uploadBlob(r.bytes, { encoding: r.mimetype })
              return result.success({
                image: blob.data.blob,
                alt: r.altText,
                aspectRatio: {
                  width: r.width,
                  height: r.height
                }
              })
            } catch (e) {
              return result.error(
                this.responseErrorToApiError('Failed to upload image to BlueSky', e)
              )
            }
          })()
        )
      )

      const images: BlueskyMediaUploadResponse[] = []
      for (const r of imageUploadsResults) {
        if (r.type === 'error') {
          return result.error<ApiError>({
            type: 'composite-error',
            status: extractStatusFrom(...imageUploadsResults),
            module: 'mastodon',
            error: 'Failed to upload images.',
            responses: imageUploadsResults
          })
        }
        images.push(r.result)
      }

      const embed =
        images.length == 0
          ? undefined
          : {
              $type: 'app.bsky.embed.images',
              images
            }

      const text =
        (post.cleanupHtml ? utils.convertHtml(post.content) : post.content.trim()) +
        (post.link ? `\n\n${post.link}` : '')

      logger.info(`Posting to BlueSky:\n${text.trim().replace(/^/gm, '  |')}`)
      const agent = await this.agent
      const rt = new RichText({ text })
      await rt.detectFacets(agent)

      // Go, go, go!
      const r = await agent.post({
        text: rt.text,
        facets: rt.facets,
        createdAt: new Date().toISOString(),
        langs: post.language ? [post.language] : undefined,
        embed
      })
      return result.success({
        module: 'bluesky',
        uri: r.uri,
        cid: r.cid
      })
    } catch (e) {
      return result.error(this.responseErrorToApiError('Failed to post to BlueSky', e))
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
        module: 'bluesky'
      })
    }

    return await this.createPost({ ...post, content })
  }

  createPostHttpRoute = async (req: Request, res: Response): Promise<void> => {
    const r = await this.createPostRoute(req.body)
    if (r.type === 'success') {
      res.status(200).send(r.result)
    } else {
      writeErrorToResponse(res, r.error)
    }
  }
}
