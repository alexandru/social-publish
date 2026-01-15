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

  test("Content opaque type validation") {
    // Valid content
    val validContent = Content.apply("Valid content")
    assert(validContent.isRight)
    assertEquals(validContent.map(_.value), Right("Valid content"))

    // Empty content should fail
    val emptyContent = Content.apply("")
    assert(emptyContent.isLeft)

    // Content over 1000 characters should fail
    val longContent = Content.apply("a" * 1001)
    assert(longContent.isLeft)

    // Content at exactly 1000 characters should pass
    val maxContent = Content.apply("a" * 1000)
    assert(maxContent.isRight)
  }

  test("Content JSON serialization") {
    val content = Content.unsafe("Test content")
    val json = content.asJson
    assertEquals(json.as[Content].map(_.value), Right("Test content"))

    // Test that invalid content is rejected during deserialization
    val emptyJson = parse("""""""")
    assert(emptyJson.isRight)
    val decoded = emptyJson.flatMap(_.as[Content])
    assert(decoded.isLeft)
  }

  test("Target enum supports linkedin") {
    val json = parse(""""linkedin"""")
    assert(json.isRight)
    val decoded = json.flatMap(_.as[Target])
    assertEquals(decoded, Right(Target.LinkedIn))
  }

  test("NewPostRequest serialization") {
    val request = NewPostRequest(
      content = Content.unsafe("Test post"),
      targets = Some(List(Target.Bluesky, Target.Mastodon)),
      link = Some("https://example.com"),
      language = Some("en"),
      cleanupHtml = Some(true),
      images = Some(List(UUID.randomUUID()))
    )

    val json = request.asJson
    val decoded = json.as[NewPostRequest]
    assert(decoded.isRight)
    assertEquals(decoded.map(_.content.value), Right("Test post"))
  }

  test("NewPostResponse.Bluesky serialization") {
    val response: NewPostResponse = NewPostResponse.Bluesky("test-uri", Some("test-cid"))
    val json = response.asJson

    val moduleField = json.hcursor.get[String]("module")
    assertEquals(moduleField, Right("bluesky"))

    val uriField = json.hcursor.get[String]("uri")
    assertEquals(uriField, Right("test-uri"))
  }

  test("NewPostResponse.Mastodon serialization") {
    val response: NewPostResponse = NewPostResponse.Mastodon("test-uri")
    val json = response.asJson

    val moduleField = json.hcursor.get[String]("module")
    assertEquals(moduleField, Right("mastodon"))
  }

}
