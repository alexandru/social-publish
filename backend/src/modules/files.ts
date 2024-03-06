import { FilesDatabase, Upload } from '../db/files'
import { Request, Response } from 'express'
import multer from 'multer'
import logger from '../utils/logger'
import fs from 'fs'
import path from 'path'

export type FilesConfig = {
  uploadedFilesPath: string
}

export class FilesModule {
  private constructor(
    public config: FilesConfig & {
      newUploadsPath: string
      processedPath: string
    },
    private db: FilesDatabase
  ) {}

  static init = (config: FilesConfig, db: FilesDatabase) => {
    const cfg = {
      ...config,
      newUploadsPath: `${config.uploadedFilesPath}/new`,
      processedPath: `${config.uploadedFilesPath}/processed`
    }
    for (const dir of [cfg.uploadedFilesPath, cfg.newUploadsPath, cfg.processedPath])
      try {
        if (!fs.existsSync(dir)) {
          fs.mkdirSync(dir, { recursive: true })
        }
      } catch (e) {
        logger.error(`Error creating directory ${dir}:`, e)
      }
    return new FilesModule(cfg, db)
  }

  middleware = multer({ dest: this.config.newUploadsPath }).array('files', 4)

  uploadFilesHttpRoute = async (req: Request, res: Response) => {
    const files = req.files
    if (!files || files.length === 0)
      return res.status(400).send({
        error: 'No files were uploaded.'
      })

    // create directory if not exists
    const dir = `${this.config.uploadedFilesPath}/processed`
    if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true })

    const uploads: Upload[] = []
    // req.file is the uploaded file
    for (const file of files as Express.Multer.File[]) {
      // Create database record
      const row = await this.db.createFile(file)
      uploads.push(row)
      // Move the file to the uploaded files directory
      const newFilePath = `${dir}/${row.uuid}`
      fs.renameSync(file.path, newFilePath)
      logger.info(`Uploaded file:`, row)
    }
    res.status(200).send(uploads)
  }

  getUploadedFileRoute = async (req: Request, res: Response) => {
    const uuid = req.params.uuid

    // Fetch the file record from the database
    const fileRecord = await this.db.getFileByUuid(uuid)

    if (!fileRecord)
      return res.status(404).send({
        error: 'File not found.'
      })

    // Construct the file path
    const filePath = path.join(this.config.processedPath, fileRecord.uuid)

    // Check if the file exists
    if (!fs.existsSync(filePath)) {
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
