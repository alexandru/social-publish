package socialpublish.frontend.pages

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._

object Home {
  val component = ScalaFnComponent[Unit] { _ =>
    <.div(
      ^.cls := "home",
      <.section(
        ^.cls := "section",
        <.div(
          ^.cls := "container block",
          <.h1(^.cls := "title", "Social Publish"),
          <.p(^.cls := "subtitle", "Spam all your social media accounts at once!")
        ),
        <.div(
          ^.cls := "container box",
          <.ul(
            ^.cls := "content is-medium",
            <.li(
              <.a(^.href := "/rss", ^.target := "_blank", "RSS")
            ),
            <.li(
              <.a(
                ^.href := "https://github.com/alexandru/social-publish",
                ^.target := "_blank",
                ^.rel := "noreferrer",
                "GitHub"
              )
            )
          )
        )
      )
    )
  }
}
