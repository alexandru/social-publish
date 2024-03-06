import { DBConnection } from "./base"

export interface Post extends PostPayload {
  uuid: string
  createdAt: Date
}

export interface PostPayload {
  content: string
  link?: string
  tags?: string[]
  language?: string
}

export class PostsDatabase {
  private db: DBConnection
  constructor(db: DBConnection) {
    this.db = db
  }

  async createPost(payload: PostPayload): Promise<Post> {
    const post: Post = {
      uuid: require('uuid').v4(),
      createdAt: new Date(),
      ...payload
    }
    await this.db.run(
      'INSERT INTO posts (uuid, kind, json, created_at) VALUES (?, ?, ?, ?)',
      post.uuid,
      'post',
      JSON.stringify(payload),
      post.createdAt.getTime()
    )
    return post
  }

  async getPosts(): Promise<Post[]> {
    const rows = await this.db.all(
      "SELECT * FROM posts WHERE kind = 'post' ORDER BY created_at DESC"
    )
    if (!rows) return []
    return rows.map((row: any) => ({
      uuid: row.uuid,
      createdAt: new Date(row.created_at),
      ...JSON.parse(row.json)
    }))
  }

  async getPost(uuid: string): Promise<Post | null> {
    const row = await this.db.getOne("SELECT * FROM posts WHERE kind = 'post' AND uuid = ?", uuid)
    if (!row) return null
    return {
      uuid: row.uuid,
      createdAt: new Date(row.created_at),
      ...JSON.parse(row.json)
    }
  }
}
