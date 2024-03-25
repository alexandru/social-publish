import { DBConnection } from './base'
import { DocumentsDatabase } from './documents'

export interface Post extends PostPayload {
  uuid: string
  targets: string[]
  createdAt: Date
}

export interface PostPayload {
  content: string
  link?: string
  tags?: string[]
  language?: string
  images?: string[]
}

export class PostsDatabase {
  constructor(private docs: DocumentsDatabase) {}

  static init = async (db: DBConnection) => {
    const docs = await DocumentsDatabase.init(db)
    return new PostsDatabase(docs)
  }

  async create(payload: PostPayload, targets: string[]): Promise<Post> {
    const row = await this.docs.createOrUpdate({
      kind: 'post',
      payload: JSON.stringify(payload),
      tags: targets.map((t) => ({ name: t, kind: 'target' }))
    })
    return {
      uuid: row.uuid,
      createdAt: row.createdAt,
      targets,
      ...payload
    }
  }

  async getAll(): Promise<Post[]> {
    const rows = await this.docs.getAll('post', 'created_at DESC')
    return rows.map((row) => ({
      uuid: row.uuid,
      createdAt: row.createdAt,
      targets: row.tags.filter((t) => t.kind === 'target').map((t) => t.name),
      ...JSON.parse(row.payload)
    }))
  }

  async searchByUUID(uuid: string): Promise<Post | null> {
    const row = await this.docs.searchByUUID(uuid)
    if (!row) return null
    return {
      uuid: row.uuid,
      createdAt: row.createdAt,
      targets: row.tags.filter((t) => t.kind === 'target').map((t) => t.name),
      ...JSON.parse(row.payload)
    }
  }
}
