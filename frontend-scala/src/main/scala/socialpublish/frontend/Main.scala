package socialpublish.frontend

import org.scalajs.dom
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._

import scala.scalajs.js
import scala.scalajs.js.annotation._

@main
def main(): Unit = {
  val container = Option(dom.document.getElementById("app")).getOrElse {
    val elem = dom.document.createElement("div")
    elem.id = "app"
    dom.document.body.appendChild(elem)
    elem
  }

  App().renderIntoDOM(container)
}

val App = ScalaFnComponent[Unit] { _ =>
  <.div(
    "Loading..."
  )
}
