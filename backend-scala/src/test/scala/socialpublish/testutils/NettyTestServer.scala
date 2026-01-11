package socialpublish.testutils

import cats.effect.{IO, Resource}
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.Uri
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.netty.NettyConfig
import sttp.tapir.server.netty.cats.NettyCatsServer
import scala.concurrent.duration.*

final case class NettyTestServer(baseUri: Uri)

object NettyTestServer {

  def resource(
    endpoints: List[ServerEndpoint[Any, IO]]
  ): Resource[IO, NettyTestServer] = {
    val fs2Endpoints = endpoints.map(toFs2)
    val nettyConfig = NettyConfig.default.copy(gracefulShutdownTimeout = Some(1.second))

    NettyCatsServer.io(nettyConfig).flatMap { server =>
      Resource.make(
        server
          .host("127.0.0.1")
          .port(0)
          .addEndpoints(fs2Endpoints)
          .start()
      )(binding => binding.stop()).map { binding =>
        NettyTestServer(Uri.unsafeParse(s"http://${binding.hostName}:${binding.port}"))
      }
    }
  }

  private def toFs2(endpoint: ServerEndpoint[Any, IO]): ServerEndpoint[Fs2Streams[IO], IO] =
    endpoint.asInstanceOf[ServerEndpoint[Fs2Streams[IO], IO]]

}
