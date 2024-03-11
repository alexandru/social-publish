import { DBConnection, Migration } from './base'
import { v5 as uuidv5 } from 'uuid'

export type Upload = UploadPayload & {
  uuid: string
  createdAt: Date
}

export type UploadPayload = {
  hash: string
  originalname: string
  mimetype: string
  size: number
  altText?: string
  imageWidth?: number
  imageHeight?: number
}

const uuidNamespace = '5b9ba0d0-8825-4c51-a34e-f849613dbcac'

const migrations: Migration[] = [
  {
    ddl: [
      `
      |CREATE TABLE IF NOT EXISTS uploads (
      |    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
      |    hash VARCHAR(64) NOT NULL,
      |    originalname VARCHAR(255) NOT NULL,
      |    mimetype VARCHAR(255),
      |    size INTEGER,
      |    altText TEXT,
      |    imageWidth INTEGER,
      |    imageHeight INTEGER,
      |    createdAt INTEGER NOT NULL
      |)
      `.replace(/^\s*\|/gm, ''),
      `
      |CREATE INDEX IF NOT EXISTS uploads_createdAt
      |    ON uploads(createdAt)
      `.replace(/^\s*\|/gm, '')
    ],
    testIfApplied: async (db) =>
      !!(await db.getOne("SELECT 1 FROM sqlite_master WHERE type='table' AND name='uploads'"))
  }
]

export class FilesDatabase {
  private constructor(private db: DBConnection) {}

  static init = async (db: DBConnection) => {
    await db.migrate(migrations)
    return new FilesDatabase(db)
  }

  createFile = (payload: UploadPayload): Promise<Upload> =>
    this.db.transaction(async () => {
      const uuid = uuidv5(
        [
          `h:${payload.hash}`,
          `n:${payload.originalname}`,
          `a:${payload.altText}` ?? '',
          `w:${payload.imageWidth ?? ''}`,
          `h:${payload.imageHeight ?? ''}`,
          `m:${payload.mimetype}`
        ].join('/'),
        uuidNamespace
      )
      const row = await this.db.getOne(`SELECT * FROM uploads WHERE uuid = ?`, uuid)
      if (row)
        return {
          ...row,
          createdAt: new Date(row.createdAt)
        }

      const now = Date.now()
      const upload: Upload = {
        uuid,
        hash: payload.hash,
        originalname: payload.originalname,
        mimetype: payload.mimetype,
        size: payload.size,
        altText: payload.altText,
        imageWidth: payload.imageWidth,
        imageHeight: payload.imageHeight,
        createdAt: new Date(now)
      }
      await this.db.executeUpdate(
        `
        |INSERT INTO uploads
        |    (uuid, hash, originalname, mimetype, size, altText, imageWidth, imageHeight, createdAt)
        |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        `.replace(/^\s*\|/gm, ''),
        ...Object.values(upload)
      )
      return upload
    })

  getFileByUuid = async (uuid: string): Promise<Upload | null> => {
    const row = await this.db.getOne(
      `
      |SELECT * FROM uploads
      |WHERE uuid = ?
      `.replace(/^\s*\|/gm, ''),
      uuid
    )
    if (!row) return null
    return {
      uuid: row.uuid,
      hash: row.hash,
      originalname: row.originalname,
      mimetype: row.mimetype,
      size: row.size,
      altText: row.altText,
      imageWidth: row.imageWidth,
      imageHeight: row.imageHeight,
      createdAt: new Date(row.createdAt)
    }
  }
}
