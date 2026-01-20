package socialpublish.frontend

import org.scalajs.dom
import typings.react.mod.{createElement => h}
import typings.reactDom.mod.{render => renderReact}
import socialpublish.frontend.components.NavBar
import socialpublish.frontend.pages.Home

import scala.scalajs.js.annotation._

object Main {
  @JSExportTopLevel("main")
  def main(): Unit = {
    val container = dom.document.getElementById("app")
    
    renderReact(
      h(
        "div",
        null,
        NavBar.component,
        h("main", null, Home.component)
      ),
      container
    )
  }
}
