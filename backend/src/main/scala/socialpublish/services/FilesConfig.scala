package socialpublish.services

import com.monovore.decline.Opts
import cats.syntax.all.*
import java.nio.file.Path

case class FilesConfig(uploadedFilesPath: Path)

object FilesConfig {

  private def envOrDefault(envName: String, default: => String): String =
    sys.env.getOrElse(envName, default)

  private val uploadedFilesPathOpt =
    Opts.option[Path](
      "uploaded-files-path",
      help = "Directory where uploaded files are stored and processed",
      metavar = "path"
    ).orElse(Opts(Path.of(envOrDefault("UPLOADED_FILES_PATH", "/var/lib/social-publish/uploads"))))

  val opts: Opts[FilesConfig] = uploadedFilesPathOpt.map(FilesConfig.apply)
}
