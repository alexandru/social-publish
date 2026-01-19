package socialpublish.http

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

import scala.concurrent.duration.*

object HttpServer {

  def resource(
    config: ServerConfig,
    routes: Routes
  ): Resource[IO, Server] = {
    val httpApp = routes.httpRoutes.orNotFound

    val host = Host.fromString("0.0.0.0").getOrElse(
      throw new IllegalArgumentException("Invalid host")
    )
    val port = Port.fromInt(config.port).getOrElse(
      throw new IllegalArgumentException(s"Invalid port: ${config.port}")
    )

    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(httpApp)
      .withShutdownTimeout(1.second)
      .build
  }

}
