import { ApiError, buildErrorFromResponse, writeErrorToResponse } from '../models/errors'
import { DocumentsDatabase } from '../db/documents'
import { HttpConfig } from './http'
import { NewPostRequest, NewPostResponse, UnvalidatedNewPostRequest } from '../models/posts'
import { Request, Response } from 'express'
import crypto from 'crypto'
import logger from '../utils/logger'
import OAuth from 'oauth-1.0a'
import qs from 'querystring'
import result, { Result } from '../models/result'
import utils from '../utils/text'
import { FilesModule } from './files'

export type TwitterAuthConfig = {
  twitterOauth1ConsumerKey: string
  twitterOauth1ConsumerSecret: string
}

type CreateNewPostRequest = {
  text: string
  media?: { media_ids: string[] }
}

type TwitterMediaUploadResponse = {
  id: string
}

type AuthorizedToken = {
  key: string
  secret: string
}

const authorizeURL = new URL('https://api.twitter.com/oauth/authorize')
const accessTokenURL = 'https://api.twitter.com/oauth/access_token'

const callback = (baseUrl: string, jwtAccessToken: string) =>
  `${baseUrl}/api/twitter/callback?access_token=${encodeURIComponent(jwtAccessToken)}`
const requestTokenURL = (baseUrl: string, jwtAccessToken: string) =>
  `https://api.twitter.com/oauth/request_token?oauth_callback=${encodeURIComponent(callback(baseUrl, jwtAccessToken))}&x_auth_access_type=write`

export class TwitterApiModule {
  constructor(
    private config: TwitterAuthConfig & HttpConfig,
    private docsDb: DocumentsDatabase,
    private files: FilesModule
  ) {}

  private oauth = new OAuth({
    consumer: {
      key: this.config.twitterOauth1ConsumerKey,
      secret: this.config.twitterOauth1ConsumerSecret
    },
    signature_method: 'HMAC-SHA1',
    hash_function: (baseString, key) =>
      crypto.createHmac('sha1', key).update(baseString).digest('base64')
  })

  private requestToken = async (jwtAccessToken: string): Promise<Result<any, ApiError>> => {
    const tokenUrl = requestTokenURL(this.config.baseUrl, jwtAccessToken)
    const authHeader = this.oauth.toHeader(
      this.oauth.authorize({
        url: tokenUrl,
        method: 'POST'
      })
    )
    const response = await fetch(tokenUrl, {
      method: 'POST',
      headers: {
        Authorization: authHeader['Authorization']
      }
    })
    if (!response.ok) {
      return result.error(await buildErrorFromResponse(response, 'twitter'))
    }
    const body = await response.text()
    const parsed = qs.parse(body)
    return result.success({
      oauth_token: parsed.oauth_token,
      oauth_token_secret: parsed.oauth_token_secret
    })
  }

  buildAuthorizeURL = async (jwtAccessToken: string): Promise<Result<string, ApiError>> => {
    const requestToken = await this.requestToken(jwtAccessToken)
    if (requestToken.type === 'error') {
      return requestToken
    }
    authorizeURL.searchParams.set('oauth_token', requestToken.result.oauth_token)
    return result.success(authorizeURL.href)
  }

  private buildAuthHeader = (url: string, token: AuthorizedToken): OAuth.Header =>
    this.oauth.toHeader(
      this.oauth.authorize(
        {
          url,
          method: 'POST'
        },
        token
      )
    )

  private restoreOauthTokenFromDb = async () => {
    const doc = await this.docsDb.searchByKey('twitter-oauth-token')
    if (doc && doc.payload) {
      return JSON.parse(doc.payload) as AuthorizedToken
    }
    return null
  }

  hasTwitterAuth = async (): Promise<boolean> => {
    logger.info('Checking Twitter auth status...')
    const token = await this.restoreOauthTokenFromDb()
    logger.info('Token taken from DB:', token)
    return !!token
  }

  saveOauthToken = async (token: string, verifier: string): Promise<Result<void, ApiError>> => {
    const authHeader = this.oauth.toHeader(
      this.oauth.authorize({
        url: accessTokenURL,
        method: 'POST'
      })
    )
    const path = `https://api.twitter.com/oauth/access_token?oauth_verifier=${verifier}&oauth_token=${token}`
    const req = await fetch(path, {
      method: 'GET',
      headers: {
        Authorization: authHeader['Authorization']
      }
    })
    if (!req.ok) {
      return result.error(
        await buildErrorFromResponse(req, 'twitter', {
          action: 'save-oauth'
        })
      )
    }
    const body = qs.parse(await req.text())
    const authorizedToken: AuthorizedToken = {
      key: body.oauth_token as string,
      secret: body.oauth_token_secret as string
    }
    await this.docsDb.createOrUpdate(
      'twitter-oauth-token',
      JSON.stringify(authorizedToken),
      'twitter-oauth-token'
    )
    return result.success(undefined)
  }

  authorizeHttpRoute = async (req: Request, res: Response) => {
    if (!req.jwtToken) {
      return res.status(401).send({ error: 'Unauthorized' })
    }
    const url = await this.buildAuthorizeURL(req.jwtToken)
    if (url.type === 'error') return writeErrorToResponse(res, url.error)
    res.redirect(url.result)
  }

  authCallbackHttpRoute = async (req: Request, res: Response) => {
    const token = req.query.oauth_token as string | undefined
    const verifier = req.query.oauth_verifier as string | undefined
    if (!token || !verifier) {
      return res.status(400).send({ error: 'Invalid request' })
    }

    logger.info('Twitter auth callback:', { token, verifier })
    const r = await this.saveOauthToken(token, verifier)
    if (r.type === 'error') return writeErrorToResponse(res, r.error)
    res
      .set('Cache-Control', 'no-store, no-cache, must-revalidate, private')
      .set('Pragma', 'no-cache')
      .set('Expires', '0')
      .redirect('/account')
  }

  statusHttpRoute = async (_req: Request, res: Response) => {
    const row = await this.docsDb.searchByKey('twitter-oauth-token')
    if (!row) return res.status(404).send({ error: 'Not found' })
    return res.status(200).send({
      createdAt: row.createdAt.getTime()
    })
  }

  uploadMedia =
    (token: AuthorizedToken) =>
    async (uuid: string): Promise<Result<TwitterMediaUploadResponse, ApiError>> => {
      try {
        const r = await this.files.readImageFile(uuid)
        if (r === null)
          return result.error({
            type: 'validation-error',
            module: 'twitter',
            status: 404,
            error: 'Failed to read image file — uuid: ' + uuid
          })

        const url = 'https://upload.twitter.com/1.1/media/upload.json'
        const authHeader = this.buildAuthHeader(url, token)
        const formData = new FormData()
        formData.append('media', new Blob([r.bytes], { type: r.mimetype }), r.originalname)
        formData.append('media_category', 'tweet_image')
        const response = await fetch(url, {
          method: 'POST',
          headers: {
            Authorization: authHeader['Authorization']
          },
          body: formData
        })
        if (!response.ok) {
          return result.error(
            await buildErrorFromResponse(response, 'twitter', {
              action: 'upload-media'
            })
          )
        }
        const data = await response.json()
        const mediaIdString = data['media_id_string'] as string

        if (r.altText) {
          const altTextUrl = 'https://api.twitter.com/1.1/media/metadata/create.json'
          const authHeader = this.buildAuthHeader(altTextUrl, token)
          const altTextResponse = await fetch(altTextUrl, {
            method: 'POST',
            headers: {
              Authorization: authHeader['Authorization'],
              'Content-Type': 'application/json'
            },
            body: JSON.stringify({
              media_id: mediaIdString,
              alt_text: { text: r.altText }
            })
          })
          if (!altTextResponse.ok) {
            return result.error(
              await buildErrorFromResponse(altTextResponse, 'twitter', {
                action: 'upload-media-alt-text'
              })
            )
          }
        }

        return result.success({
          id: mediaIdString
        })
      } catch (err) {
        logger.error(`Failed to upload media (twitter) — uuid ${uuid}:`, err)
        return result.error({
          type: 'caught-exception',
          error: `Failed to upload media — uuid: ${uuid}`,
          status: 500,
          module: 'twitter'
        })
      }
    }

  createPost = async (post: NewPostRequest): Promise<Result<NewPostResponse, ApiError>> => {
    const createPostURL = 'https://api.twitter.com/2/tweets'
    const token = await this.restoreOauthTokenFromDb()
    if (!token) {
      return result.error({
        type: 'validation-error',
        status: 401,
        error: 'Unauthorized: Missing Twitter OAuth token!',
        module: 'twitter'
      })
    }

    const images: string[] = []
    if (post.images) {
      const rs = await Promise.all(post.images.map(this.uploadMedia(token)))
      logger.info('Uploaded images:', rs)

      for (const r of rs) {
        if (r.type === 'success') {
          images.push(r.result.id)
        } else {
          return result.error({
            type: 'composite-error',
            status: 502,
            module: 'twitter',
            error: 'Failed to upload images.',
            responses: rs
          })
        }
      }
    }

    const status =
      (post.cleanupHtml ? utils.convertHtml(post.content) : post.content.trim()) +
      (post.link ? `\n\n${post.link}` : '')

    // Create the post
    const data: CreateNewPostRequest = {
      text: status
    }
    if (images.length > 0) {
      data.media = { media_ids: images }
    }
    const authHeader = this.buildAuthHeader(createPostURL, token)
    const response = await fetch(createPostURL, {
      method: 'POST',
      headers: {
        Authorization: authHeader['Authorization'],
        'Content-Type': 'application/json',
        Accept: 'application/json'
      },
      body: JSON.stringify(data)
    })
    if (!response.ok) {
      return result.error(
        await buildErrorFromResponse(response, 'twitter', {
          action: 'create-post'
        })
      )
    }

    const body = await response.json()
    logger.info('Twitter create-post response:', body)
    return result.success({
      module: 'twitter',
      id: body.data.id
    })
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
        module: 'twitter'
      })
    }

    return await this.createPost({ ...post, content })
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
