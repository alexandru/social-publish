import sqlite from 'sqlite3'
import logger from '../utils/logger'

export interface Migration {
  ddl: string[]
  testIfApplied: (db: DBConnection) => Promise<boolean>
}

export type DBConfig = {
  dbPath: string
}

export class DBConnection {
  private underlying: sqlite.Database

  constructor(db: sqlite.Database) {
    this.underlying = db
  }

  async getOne(sql: string, ...params: any[]): Promise<any> {
    return new Promise<any>((resolve, reject) => {
      this.underlying.get(sql, params, (err, row) => {
        if (err) reject(err)
        else resolve(row)
      })
    })
  }

  async run(sql: string, ...params: any[]): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.underlying.run(sql, params, (err) => {
        if (err) reject(err)
        else resolve()
      })
    })
  }

  async all(sql: string, ...params: any[]): Promise<any[]> {
    return new Promise<any[]>((resolve, reject) => {
      this.underlying.all(sql, params, (err, rows) => {
        if (err) reject(err)
        else resolve(rows)
      })
    })
  }

  async transaction<A>(f: () => Promise<A>): Promise<A> {
    return new Promise<A>((resolve, reject) => {
      this.underlying.serialize(async () => {
        await this.run('BEGIN TRANSACTION')
        let isUserError = true
        try {
          const result = await f()
          isUserError = false
          await this.run('COMMIT')
          resolve(result)
        } catch (err) {
          if (isUserError)
            try {
              await this.run('ROLLBACK')
            } catch (rollbackErr) {
              logger.error('Error rolling back transaction:', rollbackErr)
            }
          reject(err)
        }
      })
    })
  }

  close(): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.underlying.close((err) => {
        if (err) {
          reject(err)
        } else {
          resolve()
        }
      })
    })
  }

  migrate = async (migrations: Migration[]) => {
    for (const migration of migrations) {
      const applied = await migration.testIfApplied(this)
      if (!applied)
        for (const ddl of migration.ddl) {
          logger.info(`Executing migration:\n${ddl}`)
          await this.run(ddl)
        }
    }
  }
}

export const withBaseConnection =
  (config: DBConfig) =>
  async <A>(f: (db: DBConnection) => Promise<A>): Promise<A> => {

    const { dbPath: path } = config
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
    const db = new DBConnection(rawDb)
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
          logger.error('Error closing database:', err)
        } else {
          throw err
        }
      }
    }
  }

export default {
  DBConnection,
  withBaseConnection
}
