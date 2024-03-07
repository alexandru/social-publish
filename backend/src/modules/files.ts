import { FilesDatabase, Upload } from '../db/files'
import { Request, Response } from 'express'
import { body, validationResult } from 'express-validator'
import multer from 'multer'
import { calculateFileHash, readFileAsUint8Array } from '../utils/files'
import logger from '../utils/logger'
import fs from 'fs'
import path from 'path'
import { HttpConfig } from './http'
import result from '../utils/result'
import { imageSize } from 'image-size'
import sharp from 'sharp'

export type FilesConfig = HttpConfig & {
  uploadedFilesPath: string,
  maxImageSize?: [number, number]
}

export class FilesModule {
  private constructor(
    public config: FilesConfig & {
      newUploadsPath: string
      processedPath: string
      resizingPath: string
    },
    private db: FilesDatabase
  ) {}

  static init = async (config: FilesConfig, db: FilesDatabase) => {
    const cfg = {
      ...config,
      newUploadsPath: `${config.uploadedFilesPath}/new`,
      processedPath: `${config.uploadedFilesPath}/processed`,
      resizingPath: `${config.uploadedFilesPath}/resizing`,
      maxImageSize: config.maxImageSize || [1280, 720]
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

  readFile = async (file: Upload | string, resize: boolean) => {
    let upload = typeof file === 'string' ? await this.db.getFileByUuid(file) : file
    if (!upload) return null

    let path = `${this.config.processedPath}/${upload.hash}`
    const maxSize = this.config.maxImageSize
    if (resize && maxSize) {
      let path2 = `${this.config.resizingPath}/bsky-${upload.hash}`
      const w = upload.imageWidth ?? 0
      const h = upload.imageHeight ?? 0
      const [maxW, maxH] = maxSize

      let nh = 0
      let nw = 0
      if (w >= h && w > maxW) {
        nw = maxW
        nh = Math.round((h / w) * nw)
      } else if (h > maxH) {
        nh = maxH
        nw = Math.round((w / h) * nh)
      }

      logger.info(`Resizing image: ${w}x${h} -> ${nw}x${nh}`)
      if (nw > 0 && nh > 0) {
        switch (upload.mimetype) {
          case 'image/png':
            path2 = `${path2}.png`
            await sharp(path).png().resize(nw, nh).toFile(path2)
            break
          case 'image/jpeg':
            path2 = `${path2}.jpg`
            await sharp(path).jpeg({ quality: 70 }).resize(nw, nh).toFile(path2)
            break
          default:
            throw new Error(`Unsupported image type: ${upload.mimetype}`)
        }
        path = path2
        const newSize = await imageSize(path)
        upload = {
          ...upload,
          imageWidth: newSize.width,
          imageHeight: newSize.height,
          size: (await fs.promises.stat(path)).size
        }
        logger.info(`Resized image: ${path} -> ${path2}: `, upload)
      }
    }
    return {
      upload,
      bytes: new Uint8Array(await fs.promises.readFile(path))
    }
  }

  middleware = [
    multer({ dest: this.config.newUploadsPath }).single('file'),
    body('altText').isString().optional()
  ]

  processUploadedFile = async (req: Request) => {
    const errors = validationResult(req)
    if (!errors.isEmpty())
      return result.error({
        status: 400,
        error: `Invalid request: ${errors.array().join(', ')}`
      })

    const altText = req.body.altText
    const file = req.file
    if (!file)
      return result.error({
        status: 400,
        error: 'No file was uploaded.'
      })

    try {
      // create directory if not exists
      const dir = `${this.config.uploadedFilesPath}/processed`
      if (!fs.existsSync(dir)) {
        await fs.promises.mkdir(dir, { recursive: true })
      }

      // Calculate the hash of the file
      const hash = await calculateFileHash('sha256', file.path)
      // File size and type
      const imageInfo = await imageSize(file.path)
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
            status: 400,
            error:
              'Only PNG and JPEG images are supported, got: ' + (imageInfo?.type || 'undefined')
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
        status: 500,
        error: 'Error processing uploaded files.'
      })
    }
  }

  uploadFilesHttpRoute = async (req: Request, res: Response) => {
    const r = await this.processUploadedFile(req)
    if (r.type === 'success') {
      res.status(200).send(r.result)
    } else {
      res.status(r.error.status).send({ error: r.error.error })
    }
  }

  getUploadedFileRoute = async (req: Request, res: Response) => {
    const uuid = req.params.uuid
    const fileRecord = await this.db.getFileByUuid(uuid)

    if (!fileRecord)
      return res.status(404).send({
        error: 'File not found.'
      })

    // Construct the file path
    const filePath = path.join(this.config.processedPath, fileRecord.uuid)
    // Check if the file exists
    if (await !fs.existsSync(filePath)) {
      return res.status(404).send('File not found.')
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
