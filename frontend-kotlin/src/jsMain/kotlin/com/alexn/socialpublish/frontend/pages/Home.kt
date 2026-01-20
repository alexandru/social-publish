package com.alexn.socialpublish.frontend.pages

import com.alexn.socialpublish.frontend.utils.toClassName
import com.alexn.socialpublish.frontend.utils.toWindowTarget
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.li
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.section
import react.dom.html.ReactHTML.ul

val Home =
  FC<Props> {
    div {
      className = "home".toClassName()
      section {
        className = "section".toClassName()
        div {
          className = "container block".toClassName()
          h1 {
            className = "title".toClassName()
            +"Social Publish"
          }
          p {
            className = "subtitle".toClassName()
            +"Spam all your social media accounts at once!"
          }
        }

        div {
          className = "container box".toClassName()
          ul {
            className = "content is-medium".toClassName()
            li {
              a {
                href = "/rss"
                target = "_blank".toWindowTarget()
                +"RSS"
              }
            }
            li {
              a {
                href = "https://github.com/alexandru/social-publish"
                target = "_blank".toWindowTarget()
                rel = "noreferrer"
                +"GitHub"
              }
            }
          }
        }
      }
    }
  }
