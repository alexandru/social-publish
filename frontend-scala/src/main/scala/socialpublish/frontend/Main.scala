package socialpublish.frontend

import org.scalajs.dom
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._

import socialpublish.frontend.components.NavBar
import socialpublish.frontend.pages.Home

@main
def main(): Unit = {
  val container = dom.document.getElementById("app")
  
  App().renderIntoDOM(container)
}

val App = ScalaFnComponent[Unit] { _ =>
  <.div(
    NavBar.component(),
    <.main(
      Home.component()
    )
  )
}
