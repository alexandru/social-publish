package socialpublish.testutils

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import sttp.model.Uri
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import scala.concurrent.duration.*

final case class Http4sTestServer(baseUri: Uri)

object Http4sTestServer {

  def resource(
    endpoints: List[ServerEndpoint[Any, IO]]
  ): Resource[IO, Http4sTestServer] = {
    val httpApp = Http4sServerInterpreter[IO]().toRoutes(endpoints).orNotFound

    val host = Host.fromString("127.0.0.1").get
    val port = Port.fromInt(0).get

    EmberServerBuilder
      .default[IO]
      .withHost(host)
      .withPort(port)
      .withHttpApp(httpApp)
      .withShutdownTimeout(1.second)
      .build
      .map { server =>
        val uri =
          Uri.unsafeParse(s"http://${server.address.getHostString}:${server.address.getPort}")
        Http4sTestServer(uri)
      }
  }

}
