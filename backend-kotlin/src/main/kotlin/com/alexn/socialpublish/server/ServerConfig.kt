package com.alexn.socialpublish.server

import java.io.File

data class ServerConfig(
    val dbPath: String,
    val httpPort: Int,
    val baseUrl: String,
    val staticContentPaths: List<File>,
    val auth: ServerAuthConfig,
)

data class ServerAuthConfig(
    val username: String,
    val passwordHash: String,
    val jwtSecret: String
)
