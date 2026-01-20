package com.alexn.socialpublish.frontend.pages

import com.alexn.socialpublish.frontend.utils.toClassName
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section

val NotFound =
  FC<Props> {
    div {
      className = "notFound".toClassName()
      section {
        className = "section".toClassName()
        div {
          className = "container".toClassName()
          h1 {
            className = "title".toClassName()
            +"404: Not Found"
          }
          p {
            className = "subtitle".toClassName()
            +"It's gone"
          }
        }
      }
    }
  }
