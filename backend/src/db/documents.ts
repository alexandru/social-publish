import logger from '../utils/logger'
import { DBConnection, Migration } from './base'
import { v4 as uuidv4 } from 'uuid'

export interface Document {
  uuid: string
  searchKey: string
  kind: string
  payload: string
  createdAt: Date
}

export const migrations: Migration[] = [
  {
    ddl: [
      `DROP TABLE IF EXISTS posts`,
      `
      |CREATE TABLE IF NOT EXISTS documents (
      |    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
      |    search_key VARCHAR(255) UNIQUE NOT NULL,
      |    kind VARCHAR(255) NOT NULL,
      |    payload TEXT NOT NULL,
      |    created_at INTEGER NOT NULL
      |)
      `.replace(/^\s*\|/gm, ''),
      `
      |CREATE INDEX IF NOT EXISTS
      |   documents_created_at
      |ON
      |   documents(kind, created_at)
      `.replace(/^\s*\|/gm, '')
    ],
    testIfApplied: async (db) =>
      !!(await db.getOne("SELECT 1 FROM sqlite_master WHERE type='table' AND name='documents'"))
  }
]

export class DocumentsDatabase {
  private constructor(private db: DBConnection) {}

  static init = async (db: DBConnection) => {
    await db.migrate(migrations)
    return new DocumentsDatabase(db)
  }

  createOrUpdate = (kind: string, payload: string, searchKey?: string): Promise<Document> =>
    this.db.transaction(async () => {
      const existing = searchKey ? await this.searchByKey(searchKey) : null
      if (existing) {
        const updated = await this.db.executeUpdate(
          'UPDATE documents SET payload = ? WHERE search_key = ?',
          payload,
          searchKey
        )
        console.log('Updated:', updated)
        if ((updated.changes || 0) > 0)
          return {
            ...existing,
            payload
          }
        logger.warn(`Failed to update document with search key ${searchKey}`)
      }
      const uuid = uuidv4()
      const document: Document = {
        uuid,
        searchKey: searchKey || `${kind}:${uuid}`,
        kind,
        payload,
        createdAt: new Date()
      }
      await this.db.executeUpdate(
        'INSERT INTO documents (uuid, search_key, kind, payload, created_at) VALUES (?, ?, ?, ?, ?)',
        document.uuid,
        document.searchKey,
        document.kind,
        document.payload,
        document.createdAt.getTime()
      )
      return document
    })

  searchByKey = async (searchKey: string): Promise<Document | null> => {
    const row = await this.db.getOne('SELECT * FROM documents WHERE search_key = ?', searchKey)
    if (!row) return null
    return {
      uuid: row.uuid,
      kind: row.kind,
      payload: row.payload,
      searchKey: row.search_key,
      createdAt: new Date(row.created_at)
    }
  }

  searchByUUID = async (uuid: string): Promise<Document | null> => {
    const row = await this.db.getOne('SELECT * FROM documents WHERE uuid = ?', uuid)
    if (!row) return null
    return {
      uuid: row.uuid,
      kind: row.kind,
      payload: row.payload,
      searchKey: row.search_key,
      createdAt: new Date(row.created_at)
    }
  }

  getAll = async (
    kind: string,
    orderBy: 'created_at' | 'created_at DESC' = 'created_at DESC'
  ): Promise<Document[]> => {
    const rows = await this.db.all(
      `SELECT * FROM documents WHERE kind = ? ORDER BY ${orderBy}`,
      kind
    )
    if (!rows) return []
    return rows.map((row: any) => ({
      uuid: row.uuid,
      kind: row.kind,
      payload: row.payload,
      searchKey: row.search_key,
      createdAt: new Date(row.created_at)
    }))
  }
}
