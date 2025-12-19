package socialpublish.db

import doobie.*
import java.util.UUID

object Metas:
  // UUID Meta instance for Doobie
  given Meta[UUID] = Meta[String].timap(UUID.fromString)(_.toString)
