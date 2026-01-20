package socialpublish.frontend.components

import org.scalajs.dom
import typings.react.mod.{createElement => h}
import scala.scalajs.js

object NavBar {
  val component = h(
    "nav",
    js.Dynamic.literal(
      className = "navbar is-primary",
      role = "navigation"
    ).asInstanceOf[js.Object],
    h(
      "div",
      js.Dynamic.literal(className = "navbar-brand").asInstanceOf[js.Object],
      h(
        "a",
        js.Dynamic.literal(
          role = "button",
          className = "navbar-burger"
        ).asInstanceOf[js.Object],
        h("span", null),
        h("span", null),
        h("span", null)
      )
    ),
    h(
      "div",
      js.Dynamic.literal(className = "navbar-menu").asInstanceOf[js.Object],
      h(
        "div",
        js.Dynamic.literal(className = "navbar-start").asInstanceOf[js.Object],
        h(
          "a",
          js.Dynamic.literal(
            className = "navbar-item",
            href = "/"
          ).asInstanceOf[js.Object],
          h("span", js.Dynamic.literal(className = "icon").asInstanceOf[js.Object]),
          h("strong", null, "Home")
        ),
        h(
          "a",
          js.Dynamic.literal(
            className = "navbar-item",
            href = "https://github.com/alexandru/social-publish",
            target = "_blank"
          ).asInstanceOf[js.Object],
          h("span", js.Dynamic.literal(className = "icon").asInstanceOf[js.Object]),
          h("strong", null, "GitHub")
        )
      ),
      h("div", js.Dynamic.literal(className = "navbar-end").asInstanceOf[js.Object])
    )
  )
}
