import { DBConnection, Migration } from "./base";
import { v4 as uuidv4 } from 'uuid'

export type Upload = UploadPayload & {
  uuid: string,
  createdAt: Date
}

export type UploadPayload = {
  originalname: string,
  mimetype?: string,
  size?: number
}

const migrations: Migration[] = [
  {
    ddl: [
      `
      |CREATE TABLE IF NOT EXISTS uploads (
      |    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
      |    originalname VARCHAR(255) NOT NULL,
      |    mimetype VARCHAR(255),
      |    size INTEGER,
      |    created_at INTEGER NOT NULL
      |)
      `.replace(/^\s*\|/gm, ''),
      `
      |CREATE INDEX IF NOT EXISTS uploads_created_at
      |ON uploads(created_at)
      `.replace(/^\s*\|/gm, '')
    ],
    testIfApplied: async (db) =>
      !!(await db.getOne("SELECT 1 FROM sqlite_master WHERE type='table' AND name='uploads'"))
  },
]

export class FilesDatabase {
  private constructor(private db: DBConnection) {}

  static init = async (db: DBConnection) => {
    await db.migrate(migrations)
    return new FilesDatabase(db)
  }

  createFile = async (payload: UploadPayload): Promise<Upload> => {
    const uuid = uuidv4()
    const now = Date.now()
    await this.db.run(
      `
      |INSERT INTO uploads
      |    (uuid, originalname, mimetype, size, created_at)
      |VALUES (?, ?, ?, ?, ?)
      `.replace(/^\s*\|/gm, ''),
      uuid,
      payload.originalname,
      payload.mimetype,
      payload.size,
      now
    )
    return {
      ...payload,
      uuid,
      createdAt: new Date(now)
    }
  }

  getFileByUuid = async (uuid: string): Promise<Upload | null> => {
    const row = await this.db.getOne(
      `
      |SELECT
      |   uuid,
      |   originalname,
      |   mimetype,
      |   size,
      |   created_at
      |FROM uploads
      |WHERE uuid = ?
      `.replace(/^\s*\|/gm, ''),
      uuid
    )
    if (!row) return null
    return {
      uuid: row.uuid,
      originalname: row.originalname,
      mimetype: row.mimetype,
      size: row.size,
      createdAt: new Date(row.created_at)
    }
  }
}
