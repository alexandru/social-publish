package socialpublish.backend.server

import java.io.File

data class ServerConfig(
    val dbPath: String,
    val httpPort: Int,
    val baseUrl: String,
    val staticContentPaths: List<File>,
    val auth: ServerAuthConfig,
)

/** Server authentication config. Username and password are now stored in the database. */
data class ServerAuthConfig(val jwtSecret: String)
