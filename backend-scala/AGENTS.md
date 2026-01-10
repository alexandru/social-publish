# Social-Publish Backend

This project is the backend for the Social Publish project. It's purpose is to expose an HTTP API that allows the client to publish posts on social media platforms (e.g., Mastodon, Bluesky, Twitter, LinkedIn).

It's built in Scala 3, making use of functional programming and the Typelevel ecosystem of libraries.

## Building and testing

- To build the project: 
  `sbt Test/compile`
- To build and test the project: 
  `sbt Test/compile test`
- To format the source code (required):
  `sbt scalafmtAll`
