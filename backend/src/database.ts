import sqlite from 'sqlite3'

interface Migration {
  ddl: string[]
  testIfApplied: (db: Database) => Promise<boolean>
}

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

const migrations: Migration[] = [
  {
    ddl: [
      `
            |CREATE TABLE IF NOT EXISTS posts (
            |    uuid VARCHAR(36) NOT NULL PRIMARY KEY,
            |    kind VARCHAR(255) NOT NULL,
            |    json TEXT NOT NULL,
            |    created_at INTEGER NOT NULL
            |)
            `.replace(/^\s*\|/gm, ''),
      `
            |CREATE INDEX IF NOT EXISTS
            |   posts_created_at
            |ON
            |   posts(kind, created_at)
            `.replace(/^\s*\|/gm, '')
    ],
    testIfApplied: async (db) =>
      !!(await db.getOne("SELECT 1 FROM sqlite_master WHERE type='table' AND name='posts'"))
  }
]

export class Database {
  private db: sqlite.Database
  constructor(db: sqlite.Database) {
    this.db = db
  }

  async migrate() {
    for (const migration of migrations) {
      const applied = await migration.testIfApplied(this)
      if (!applied)
        for (const ddl of migration.ddl) {
          console.log(`[${new Date().toISOString()}] Executing migration:\n${ddl}`)
          await new Promise<void>((resolve, reject) => {
            this.db.run(ddl, (err) => {
              if (err) {
                reject(err)
              } else {
                resolve()
              }
            })
          })
        }
    }
  }

  async getOne(sql: string, ...params: any[]): Promise<any> {
    return new Promise<any>((resolve, reject) => {
      this.db.get(sql, params, (err, row) => {
        if (err) reject(err)
        else resolve(row)
      })
    })
  }

  async run(sql: string, ...params: any[]): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.db.run(sql, params, (err) => {
        if (err) reject(err)
        else resolve()
      })
    })
  }

  async all(sql: string, ...params: any[]): Promise<any[]> {
    return new Promise<any[]>((resolve, reject) => {
      this.db.all(sql, params, (err, rows) => {
        if (err) reject(err)
        else resolve(rows)
      })
    })
  }

  close(): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.db.close((err) => {
        if (err) {
          reject(err)
        } else {
          resolve()
        }
      })
    })
  }

  static async withConnection<A>(f: (db: Database) => Promise<A>): Promise<A> {
    const path: string | undefined = process.env.DB_PATH
    if (!path) throw new Error('DB_PATH not set')
    const rawDb = await new Promise<sqlite.Database>((resolve, reject) => {
      let ref: sqlite.Database | null = null
      ref = new sqlite.Database(path, sqlite.OPEN_READWRITE | sqlite.OPEN_CREATE, (err) => {
        if (err) {
          reject(err)
        } else {
          if (ref) {
            resolve(ref)
          } else {
            throw new Error('Database opened, but ref not set')
          }
        }
      })
    })
    const db = new Database(rawDb)
    await db.migrate()

    var errorThrown: unknown = null
    try {
      return await f(db)
    } catch (err) {
      errorThrown = err
      throw err
    } finally {
      try {
        await db.close()
      } catch (err) {
        if (errorThrown) {
          console.error('Error closing database:', err)
        } else {
          throw err
        }
      }
    }
  }
}

export class PostsDatabase {
  private db: Database
  constructor(db: Database) {
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

  static async withConnection<A>(f: (db: PostsDatabase) => Promise<A>): Promise<A> {
    return Database.withConnection((db) => f(new PostsDatabase(db)))
  }

  static async init(): Promise<void> {
    return PostsDatabase.withConnection(async () => {})
  }
}

// Initialize and migrate the database
let isInitialized = false
;(() => {
  if (isInitialized) return
  isInitialized = true
  PostsDatabase.init().catch((err) => {
    console.error(`[${new Date().toISOString()}] Failed to initialize database:`, err)
    process.exit(1)
  })
})()

export default {
  Database,
  PostsDatabase
}
