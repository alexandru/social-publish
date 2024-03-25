import logger from '../utils/logger'
import { DBConnection, Migration } from './base'
import { v4 as uuidv4 } from 'uuid'

export interface Tag {
  name: string
  kind: 'target' | 'label'
}

export interface Document {
  uuid: string
  searchKey: string
  kind: string
  tags: Tag[]
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
  },
  {
    ddl: [
      `
      |CREATE TABLE IF NOT EXISTS document_tags (
      |   document_uuid VARCHAR(36) NOT NULL,
      |   name VARCHAR(255) NOT NULL,
      |   kind VARCHAR(255) NOT NULL,
      |   PRIMARY KEY (document_uuid, name, kind)
      |)
      `.replace(/^\s*\|/gm, '')
    ],
    testIfApplied: async (db) =>
      !!(await db.getOne("SELECT 1 FROM sqlite_master WHERE type='table' AND name='document_tags'"))
  }
]

export class DocumentsDatabase {
  private constructor(private db: DBConnection) {}

  static init = async (db: DBConnection) => {
    await db.migrate(migrations)
    return new DocumentsDatabase(db)
  }

  private setDocumentTags = async (documentUUID: string, tags: Tag[]) => {
    await this.db.executeUpdate('DELETE FROM document_tags WHERE document_uuid = ?', documentUUID)
    for (const tag of tags) {
      await this.db.executeUpdate(
        'INSERT INTO document_tags (document_uuid, name, kind) VALUES (?, ?, ?)',
        documentUUID,
        tag.name,
        tag.kind
      )
    }
    if (tags && tags.length > 0) {
      logger.info(`Tags for document ${documentUUID}: ${tags.map((t) => t.name).join(', ')}`)
    }
  }

  private getDocumentTags = async (documentUUID: string): Promise<Tag[]> =>
    this.db.all('SELECT name, kind FROM document_tags WHERE document_uuid = ?', documentUUID)

  createOrUpdate = (doc: {
    kind: string
    payload: string
    searchKey?: string
    tags?: Tag[]
  }): Promise<Document> =>
    this.db.transaction(async () => {
      const existing = doc.searchKey ? await this.searchByKey(doc.searchKey) : null
      if (existing) {
        const updated = await this.db.executeUpdate(
          'UPDATE documents SET payload = ? WHERE search_key = ?',
          doc.payload,
          doc.searchKey
        )
        logger.info('Updated document:', updated)
        await this.setDocumentTags(existing.uuid, doc.tags || [])
        if ((updated.changes || 0) > 0)
          return {
            ...existing,
            payload: doc.payload,
            tags: doc.tags || []
          }
        logger.warn(`Failed to update document with search key ${doc.searchKey}`)
      }
      const uuid = uuidv4()
      const document: Document = {
        uuid,
        searchKey: doc.searchKey || `${doc.kind}:${uuid}`,
        kind: doc.kind,
        payload: doc.payload,
        tags: doc.tags || [],
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
      await this.setDocumentTags(document.uuid, document.tags)
      return document
    })

  searchByKey = async (searchKey: string): Promise<Document | null> => {
    const row = await this.db.getOne('SELECT * FROM documents WHERE search_key = ?', searchKey)
    if (!row) return null
    const tags = await this.getDocumentTags(row.uuid)
    return {
      uuid: row.uuid,
      kind: row.kind,
      payload: row.payload,
      searchKey: row.search_key,
      tags,
      createdAt: new Date(row.created_at)
    }
  }

  searchByUUID = async (uuid: string): Promise<Document | null> => {
    const row = await this.db.getOne('SELECT * FROM documents WHERE uuid = ?', uuid)
    if (!row) return null
    const tags = await this.getDocumentTags(row.uuid)
    return {
      uuid: row.uuid,
      kind: row.kind,
      payload: row.payload,
      searchKey: row.search_key,
      tags,
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
    const documents: Document[] = []
    for (const row of rows) {
      const tags = await this.getDocumentTags(row.uuid)
      documents.push({
        uuid: row.uuid,
        kind: row.kind,
        payload: row.payload,
        searchKey: row.search_key,
        tags,
        createdAt: new Date(row.created_at)
      })
    }
    return documents
  }
}
