# Social-Publish Backend

This project is the backend for the Social Publish project. It's purpose is to expose an HTTP API that allows the client to publish posts on social media platforms (e.g., Mastodon, Bluesky, Twitter, LinkedIn).

It's built in Scala 3, making use of functional programming and the Typelevel ecosystem of libraries.

## Building and testing

Project uses Scala's `sbt` build tool. Before invoking it, prefer to set this:
```bash
export SBT_NATIVE_CLIENT=true
```

Commands:
- To build the project: 
  `sbt Test/compile`
- To build and test the project: 
  `sbt Test/compile test`
- To format the source code (required):
  `sbt scalafmtAll`

## Coding style

- Prefer Scala FP patterns to Java-style code:
  * For example, prefer named tuples (case classes) to ad-hoc Java-style wrapper classes.
- Use FP, including when dealing with `IO`. DO NOT use `unsafeRunSync` in code, not even in tests.
  * Avoid using side-effectful Java APIs in production code without wrapping them in `IO` to make them safe — for instance, `UUID.randomUUID` is obviously a side-effectful call that's not safe without wrapping it in (Cats-Effect) `IO`.
* Prefer beautiful and type-safe code, prefer encapsulation over ad-hoc grouping by file type — I want to see fully-baked components, and I don't see much value in project-wide MVC-style grouping of files (e.g., models/views/controllers directories, encompassing many components, suck)
  - Components have to be encapsulated enough that they can be extracted in their own sub-projects or libraries;
  - For instance, a component is the Twitter integration, another is the Mastodon integration, and yet another component is the HTTP server exposing these integrations.
