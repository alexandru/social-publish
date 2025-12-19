import { FilesDatabase, Upload } from '../db/files'
import { Request, Response } from 'express'
import { body, validationResult } from 'express-validator'
import multer from 'multer'
import { calculateFileHash } from '../utils/files'
import logger from '../utils/logger'
import fs from 'fs'
import { HttpConfig } from './http'
import result, { Result } from '../models/result'
import { imageSize } from 'image-size'
import sharp from 'sharp'
import path from 'path'
import { ApiError, writeErrorToResponse } from '../models/errors'
import { AsyncSemaphore } from '../utils/async-semaphore'
import { hashCodeMod } from '../utils/text'

export type FilesConfig = HttpConfig & {
  uploadedFilesPath: string
  resizing?: ResizeOptions
}

export type ResizeOptions = {
  maxWidth: number
  maxHeight: number
  jpgQuality: number
  pngCompression: number
}

export type ProcessedUpload = {
  originalname: string
  mimetype: string
  altText: string | undefined
  width: number
  height: number
  size: number
  bytes: Uint8Array
}

export class FilesModule {
  private readonly _locks: AsyncSemaphore[]

  private constructor(
    public readonly config: FilesConfig & {
      newUploadsPath: string
      processedPath: string
      resizingPath: string
      resizing: ResizeOptions
    },
    private readonly db: FilesDatabase
  ) {
    this._locks = Array.from({ length: 4 }, () => new AsyncSemaphore(1))
  }

  static init = async (config: FilesConfig, db: FilesDatabase) => {
    const cfg = {
      ...config,
      newUploadsPath: `${config.uploadedFilesPath}/new`,
      processedPath: `${config.uploadedFilesPath}/processed`,
      resizingPath: `${config.uploadedFilesPath}/resizing`,
      resizing: config.resizing || {
        maxWidth: 1920,
        maxHeight: 1080,
        jpgQuality: 80,
        pngCompression: 6
      }
    }
    for (const dir of [
      cfg.uploadedFilesPath,
      cfg.newUploadsPath,
      cfg.processedPath,
      cfg.resizingPath
    ]) {
      try {
        if (!fs.existsSync(dir)) {
          await fs.promises.mkdir(dir, { recursive: true })
        }
      } catch (e) {
        logger.error(`Error creating directory ${dir}:`, e)
      }
    }
    return new FilesModule(cfg, db)
  }

  private readonly withLock =
    (key: string) =>
    async <A>(f: () => Promise<A>): Promise<A> => {
      const lock = this._locks[hashCodeMod(key, this._locks.length)]
      return lock.withLock(f)
    }

  private resizeImageFile = async (upload: Upload, width: number, height: number) => {
    const path = `${this.config.processedPath}/${upload.hash}`
    const path2 = `${this.config.resizingPath}/${upload.hash}`
    const { maxWidth, maxHeight, pngCompression, jpgQuality } = this.config.resizing

    let nh = 0
    let nw = 0
    if (width >= height && width > maxWidth) {
      nw = maxWidth
      nh = Math.round((height / width) * nw)
    } else if (height > maxHeight) {
      nh = maxHeight
      nw = Math.round((width / height) * nh)
    }

    logger.info(`Resizing image: ${width}x${height} -> ${nw}x${nh}`)
    if (nw > 0 && nh > 0) {
      const r: sharp.OutputInfo = await (async () => {
        let instance = await sharp(path)
        const { orientation } = await instance.metadata()
        switch (upload.mimetype) {
          case 'image/png':
            instance = instance.png({ compressionLevel: pngCompression })
            break
          case 'image/jpeg':
            instance = instance.jpeg({ quality: jpgQuality })
            break
          default:
            throw new Error(`Unsupported image type: ${upload.mimetype}`)
        }
        return instance.resize(nw, nh).withMetadata({ orientation }).toFile(path2)
      })()

      logger.info(`Resized image:`, r)
      const newSizeBuffer = await fs.promises.readFile(path2)
      const newSize = imageSize(new Uint8Array(newSizeBuffer))
      if (newSize.width && newSize.height)
        return {
          width: newSize.width,
          height: newSize.height,
          path: path2,
          size: (await fs.promises.stat(path2)).size
        }
    }
    return { width, height, path, size: upload.size }
  }

  readImageFile = async (file: Upload | string): Promise<ProcessedUpload | null> => {
    const upload = typeof file === 'string' ? await this.db.getFileByUuid(file) : file
    if (!upload) return null

    if (['image/png', 'image/jpeg'].indexOf(upload.mimetype) === -1) {
      throw new Error(`Unsupported image type: ${upload.mimetype}`)
    }

    return this.withLock(upload.hash)(async () => {
      const pathOrig = `${this.config.processedPath}/${upload.hash}`
      let [width, height] = [upload.imageWidth, upload.imageHeight]

      if (!width || !height) {
        const sizeBuffer = await fs.promises.readFile(pathOrig)
        const size = imageSize(new Uint8Array(sizeBuffer))
        width = size.width
        height = size.height
      }
      if (!width || !height) {
        throw new Error(`Invalid image — uuid: ${upload.uuid}`)
      }

      const resized = await this.resizeImageFile(upload, width, height)
      return {
        originalname: upload.originalname,
        mimetype: upload.mimetype,
        altText: upload.altText,
        width: resized.width,
        height: resized.height,
        size: resized.size,
        bytes: new Uint8Array(await fs.promises.readFile(resized.path))
      }
    })
  }

  middleware = [
    multer({ dest: this.config.newUploadsPath }).single('file'),
    body('altText').isString().optional()
  ]

  processUploadedFile = async (req: Request): Promise<Result<Upload, ApiError>> => {
    const errors = validationResult(req)
    if (!errors.isEmpty())
      return result.error({
        type: 'validation-error',
        status: 400,
        error: `Invalid request: ${errors.array().join(', ')}`
      })

    const altText = req.body.altText
    const file = req.file
    if (!file)
      return result.error({
        type: 'validation-error',
        status: 400,
        error: 'No file was uploaded.'
      })

    try {
      // create directory if not exists
      const dir = `${this.config.uploadedFilesPath}/processed`
      // Calculate the hash of the file
      const hash = await calculateFileHash('sha256', file.path)
      // File size and type
      const fileBuffer = await fs.promises.readFile(file.path)
      const imageInfo = imageSize(new Uint8Array(fileBuffer))
      logger.info(`Image info:`, imageInfo)

      let mimetype = file.mimetype
      switch ((imageInfo?.type || '').toLowerCase()) {
        case 'png':
          mimetype = 'image/png'
          break
        case 'jpg':
        case 'jpeg':
          mimetype = 'image/jpeg'
          break
        default:
          return result.error({
            type: 'validation-error',
            status: 400,
            error: `Only PNG and JPEG images are supported, got: ${imageInfo?.type || 'undefined'}`
          })
      }

      // Create database record
      const row = await this.db.createFile({
        ...file,
        mimetype,
        altText,
        hash,
        imageWidth: imageInfo?.width,
        imageHeight: imageInfo?.height
      })

      // Move the file to the uploaded files directory
      const newFilePath = `${dir}/${hash}`
      await fs.promises.rename(file.path, newFilePath)
      logger.info(`Uploaded file:`, row)
      return result.success(row)
    } catch (e) {
      logger.error(`Error processing uploaded files:`, e)
      return result.error({
        type: 'caught-exception',
        status: 500,
        error: 'Error processing uploaded files.',
        module: 'files'
      })
    }
  }

  uploadFilesHttpRoute = async (req: Request, res: Response) => {
    const r = await this.processUploadedFile(req)
    if (r.type === 'success') {
      return res.status(200).send(r.result)
    }
    return writeErrorToResponse(res, r.error)
  }

  private notFound: ApiError = {
    type: 'validation-error',
    status: 404,
    error: 'File not found.'
  }

  getUploadedFileRoute = async (req: Request, res: Response) => {
    const uuid = req.params.uuid
    const fileRecord = await this.db.getFileByUuid(uuid)

    if (!fileRecord) return writeErrorToResponse(res, this.notFound)

    // Construct the file path
    const filePath = path.join(this.config.processedPath, fileRecord.hash)
    // Check if the file exists
    if (await !fs.existsSync(filePath)) {
      logger.warn(`File not found: ${filePath}`, fileRecord)
      return writeErrorToResponse(res, this.notFound)
    }

    // Send the file
    res.sendFile(filePath, {
      headers: {
        'Content-Type': fileRecord.mimetype,
        'Content-Disposition': `inline; filename=${fileRecord.originalname}`
      }
    })
  }
}
