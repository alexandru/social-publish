import { PostsDatabase } from './posts'
import { DBConfig, DBConnection, withBaseConnection } from './base'

export type Database = {
  connection: DBConnection
  posts: PostsDatabase
}

export const withDatabase =
  (config: DBConfig) =>
  async <A>(f: (db: Database) => Promise<A>): Promise<A> => {
    return await withBaseConnection(config)(async (connection) => {
      const posts = await PostsDatabase.init(connection)
      return await f({ connection, posts })
    })
  }

export default {
  withDatabase
}
