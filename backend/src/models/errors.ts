import { Response } from 'express'
import { Module } from './services'
import { convertTextToJsonOrNull } from '../utils/text'
import logger from '../utils/logger'
import { Dictionary } from './base'
import result, { Result } from './result'

export type ApiError = RequestError | CaughtException | ValidationError | CompositeError

/** Return by validation actions in own code. */
export type ValidationError = {
  type: 'validation-error'
  status: number
  error: string
  module?: Module
}

/** Returned by APIs. */
export type RequestError = {
  type: 'request-error'
  status: number
  module?: Module
  error?: string
  body?: {
    asString: string
    asJson?: unknown
  }
}

/** Anything caught via try-catch. */
export type CaughtException = {
  type: 'caught-exception'
  error: unknown
  status?: number
  module?: Module
}

/** For when multiple services trigger error in parallel. */
export type CompositeError = {
  type: 'composite-error'
  status?: number
  module?: Module
  error?: string
  responses: Result<unknown, ApiError>[]
}

export type SerializedHttpError = {
  status: number
  body: {
    type: string
    error: string
    module?: Module
    apiHttpResponse?: {
      status: number
      body?: {
        asString: string
        asJson?: unknown
      }
    }
    composite?: Result<unknown, SerializedHttpError>[]
  }
}

export const serializerError = (error: ApiError): SerializedHttpError => {
  switch (error.type) {
    case 'validation-error':
      return {
        status: error.status,
        body: error
      }
    case 'caught-exception':
      return {
        status: error.status || 500,
        body: {
          type: error.type,
          error: 'Interval server error (see logs for details).',
          module: error.module
        }
      }
    case 'request-error':
      return {
        status: error.status,
        body: {
          type: error.type,
          error: 'API request error.',
          module: error.module,
          apiHttpResponse: {
            status: error.status,
            body: error.body
          }
        }
      }
    case 'composite-error':
      return {
        status: error.status || 400,
        body: {
          type: error.type,
          error: error.error || 'Multiple API requests failed.',
          module: error.module,
          composite: error.responses.map(result.leftMap(serializerError))
        }
      }
  }
}

export const writeErrorToResponse = (res: Response, error: ApiError) => {
  const serialized = serializerError(error)
  res.status(serialized.status).send(serialized.body)
}

export const buildErrorFromResponse = async (
  response: globalThis.Response,
  module: Module,
  details?: Dictionary<unknown>
): Promise<ApiError> => {
  const asString = await response.text()
  const asJson = convertTextToJsonOrNull(asString)
  logger.warn('HTTP API response error:', {
    module,
    ...(details || {}),
    status: response.status,
    body: asString
  })
  return {
    type: 'request-error',
    module,
    status: response.status,
    error: `HTTP API response error`,
    body: { asString, asJson }
  }
}

export const extractStatusFrom = (...rs: Result<unknown, ApiError>[]): number => {
  const statuses = rs.flatMap((r) => (r.type === 'error' && r.error.status ? [r.error.status] : []))
  return statuses.length ? Math.max(...statuses) : 400
}
