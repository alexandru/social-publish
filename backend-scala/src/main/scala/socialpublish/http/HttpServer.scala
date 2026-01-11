package socialpublish.http

import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.cors.CORSInterceptor
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.cats.{NettyCatsServer, NettyCatsServerOptions}
import scala.concurrent.duration.*

object HttpServer {

  def resource(
    config: ServerConfig,
    routes: Routes
  ): Resource[IO, sttp.tapir.server.netty.cats.NettyCatsServerBinding[IO]] = {
    Dispatcher.parallel[IO].flatMap { dispatcher =>
      val serverOptions = NettyCatsServerOptions.customiseInterceptors[IO](dispatcher)
        .corsInterceptor(CORSInterceptor.default)
        .options

      val endpoints = routes.endpoints.map(toFs2)
      val nettyConfig = NettyConfig.default.copy(gracefulShutdownTimeout = Some(1.second))

      NettyCatsServer.io(nettyConfig).flatMap { server =>
        Resource.make(
          server
            .options(serverOptions)
            .host("0.0.0.0")
            .port(config.port)
            .addEndpoints(endpoints)
            .start()
        )(binding => binding.stop())
      }
    }
  }

  private def toFs2(endpoint: ServerEndpoint[Any, IO]): ServerEndpoint[Fs2Streams[IO], IO] =
    endpoint.asInstanceOf[ServerEndpoint[Fs2Streams[IO], IO]]

}
