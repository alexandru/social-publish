import { PostsDatabase } from './posts'
import { DBConfig, DBConnection, withBaseConnection } from './base'

export type Database = {
  connection: DBConnection,
  posts: PostsDatabase
}

export const withDatabase =
  (config: DBConfig) =>
  async <A>(f: (db: Database) => Promise<A>): Promise<A> => {

  return await withBaseConnection(config)(async (db) => {
    const posts = new PostsDatabase(db)
    return await f({ connection: db, posts })
  })
}

export default {
  withDatabase
}
