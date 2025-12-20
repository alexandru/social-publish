package socialpublish.models

import munit.FunSuite
import io.circe.syntax.*
import io.circe.parser.*
import java.util.UUID

class DomainSpec extends FunSuite {

  test("Target enum serialization") {
    val target = Target.Bluesky
    val json = target.asJson
    val decoded = json.as[Target]
    assertEquals(decoded, Right(Target.Bluesky))
  }

  test("Target enum from string") {
    val json = parse(""""bluesky"""")
    assert(json.isRight)
    val decoded = json.flatMap(_.as[Target])
    assertEquals(decoded, Right(Target.Bluesky))
  }

  test("NewPostRequest serialization") {
    val request = NewPostRequest(
      content = "Test post",
      targets = Some(List(Target.Bluesky, Target.Mastodon)),
      link = Some("https://example.com"),
      language = Some("en"),
      cleanupHtml = Some(true),
      images = Some(List(UUID.randomUUID()))
    )

    val json = request.asJson
    val decoded = json.as[NewPostRequest]
    assert(decoded.isRight)
    assertEquals(decoded.map(_.content), Right("Test post"))
  }

  test("NewPostResponse.Bluesky serialization") {
    val response = NewPostResponse.Bluesky("test-uri", Some("test-cid"))
    val json = response.asJson

    val moduleField = json.hcursor.get[String]("module")
    assertEquals(moduleField, Right("bluesky"))

    val uriField = json.hcursor.get[String]("uri")
    assertEquals(uriField, Right("test-uri"))
  }

  test("NewPostResponse.Mastodon serialization") {
    val response = NewPostResponse.Mastodon("test-uri")
    val json = response.asJson

    val moduleField = json.hcursor.get[String]("module")
    assertEquals(moduleField, Right("mastodon"))
  }
}
