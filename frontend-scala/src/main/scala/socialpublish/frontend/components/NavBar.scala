package socialpublish.frontend.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._

object NavBar {
  val component = ScalaFnComponent[Unit] { _ =>
    <.nav(
      ^.cls := "navbar is-primary",
      ^.role := "navigation",
      ^.aria.label := "main navigation",
      <.div(
        ^.cls := "navbar-brand",
        <.a(
          ^.role := "button",
          ^.cls := "navbar-burger",
          ^.aria.label := "menu",
          ^.aria.expanded := false,
          <.span(^.aria.hidden := true),
          <.span(^.aria.hidden := true),
          <.span(^.aria.hidden := true)
        )
      ),
      <.div(
        ^.cls := "navbar-menu",
        <.div(
          ^.cls := "navbar-start",
          <.a(
            ^.cls := "navbar-item",
            ^.href := "/",
            <.span(^.cls := "icon"),
            <.strong("Home")
          ),
          <.a(
            ^.cls := "navbar-item",
            ^.href := "https://github.com/alexandru/social-publish",
            ^.target := "_blank",
            <.span(^.cls := "icon"),
            <.strong("GitHub")
          )
        ),
        <.div(
          ^.cls := "navbar-end"
        )
      )
    )
  }
}
