import { BlobRef, BskyAgent, RichText } from '@atproto/api'
import utils from '../utils/text'
import {
  PostError,
  PostHttpResponse,
  PostRequest,
  PostResponse,
  UnvalidatedPostRequest
} from '../models'
import { Request, Response } from 'express'
import logger from '../utils/logger'
import { FilesModule } from './files'

export type BlueskyApiConfig = {
  blueskyService: string
  blueskyUsername: string
  blueskyPassword: string
}

export class BlueskyApiModule {
  constructor(
    public config: BlueskyApiConfig,
    private agent: BskyAgent,
    private files: FilesModule,
  ) {}

  static create = async (
    config: BlueskyApiConfig,
    files: FilesModule,
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

  createPost = async (post: PostRequest): Promise<PostResponse | PostError> => {
    type Image = {
      image: BlobRef,
      alt?: string,
      aspectRatio?: {
        width: number,
        height: number
      }
    }
    try {
      const images: Image[] = []
      for (const imageUuid of post.images || []) {
        const r = await this.files.readFile(imageUuid, true)
        if (!r) return { isSuccessful: false, error: `Image not found: ${imageUuid}` }

        const { upload, bytes } = r
        const blob = await this.agent.uploadBlob(bytes, { encoding: upload.mimetype})
        images.push({
          image: blob.data.blob,
          alt: upload?.altText,
          aspectRatio: (upload.imageWidth && upload.imageHeight)
            ? {
              width: upload.imageWidth,
              height: upload.imageHeight,
            }
            : undefined
        })
      }

      const embed = images.length == 0 ? undefined : {
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

      return { isSuccessful: true, ...r }
    } catch (e) {
      logger.error("Failed to post to BlueSky:", e)
      const eany = e as any
      if (eany.status && eany.error)
        return {
          isSuccessful: false,
          status: eany.status,
          error: eany.error
        }
      else
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
