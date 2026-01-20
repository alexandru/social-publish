package socialpublish.frontend.pages

import org.scalajs.dom
import typings.react.mod.{createElement => h}
import scala.scalajs.js

object Home {
  val component = h(
    "div",
    js.Dynamic.literal(className = "home").asInstanceOf[js.Object],
    h(
      "section",
      js.Dynamic.literal(className = "section").asInstanceOf[js.Object],
      h(
        "div",
        js.Dynamic.literal(className = "container block").asInstanceOf[js.Object],
        h("h1", js.Dynamic.literal(className = "title").asInstanceOf[js.Object], "Social Publish"),
        h("p", js.Dynamic.literal(className = "subtitle").asInstanceOf[js.Object], "Spam all your social media accounts at once!")
      ),
      h(
        "div",
        js.Dynamic.literal(className = "container box").asInstanceOf[js.Object],
        h(
          "ul",
          js.Dynamic.literal(className = "content is-medium").asInstanceOf[js.Object],
          h(
            "li",
            null,
            h(
              "a",
              js.Dynamic.literal(
                href = "/rss",
                target = "_blank"
              ).asInstanceOf[js.Object],
              "RSS"
            )
          ),
          h(
            "li",
            null,
            h(
              "a",
              js.Dynamic.literal(
                href = "https://github.com/alexandru/social-publish",
                target = "_blank",
                rel = "noreferrer"
              ).asInstanceOf[js.Object],
              "GitHub"
            )
          )
        )
      )
    )
  )
}
