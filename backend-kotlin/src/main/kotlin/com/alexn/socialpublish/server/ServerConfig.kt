package com.alexn.socialpublish.server

data class ServerConfig(
    val dbPath: String,
    val httpPort: Int,
    val baseUrl: String,
    val auth: ServerAuthConfig,
)

data class ServerAuthConfig(
    val username: String,
    val password: String,
    val jwtSecret: String,
)
