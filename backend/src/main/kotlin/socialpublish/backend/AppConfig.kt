package socialpublish.backend

import socialpublish.backend.modules.FilesConfig
import socialpublish.backend.server.ServerConfig

data class AppConfig(val server: ServerConfig, val files: FilesConfig)
