package socialpublish.testutils

import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Host, Port}
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import sttp.model.Uri

import scala.concurrent.duration.*

final case class Http4sTestServer(baseUri: Uri)

object Http4sTestServer {

  def resource(
    routes: HttpRoutes[IO]
  ): Resource[IO, Http4sTestServer] = {
    val httpApp = routes.orNotFound

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
