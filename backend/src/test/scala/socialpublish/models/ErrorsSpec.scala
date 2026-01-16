package socialpublish.models

import cats.effect.IO
import cats.mtl.Handle
import cats.mtl.syntax.all.*
import munit.CatsEffectSuite

class ErrorsSpec extends CatsEffectSuite {

  test("ApiError.validationError creates correct error") {
    val error = ApiError.validationError("Test error", "test-module")
    assertEquals(error.status, 400)
    assertEquals(error.message, "Test error")
    assertEquals(error.module, "test-module")
  }

  test("ApiError.notFound creates 404 error") {
    val error = ApiError.notFound("Resource not found")
    assertEquals(error.status, 404)
    assertEquals(error.message, "Resource not found")
    assertEquals(error.module, "server")
  }

  test("ApiError.unauthorized creates 401 error") {
    val error = ApiError.unauthorized("Invalid token")
    assertEquals(error.status, 401)
    assertEquals(error.message, "Invalid token")
    assertEquals(error.module, "auth")
  }

  test("Handle.attempt returns successful results") {
    Handle[IO, ApiError].attempt(IO.pure("test value")).map { either =>
      assertEquals(either, Right("test value"))
    }
  }

  test("ApiError.raise lifts errors into Handle.attempt") {
    val error = ApiError.validationError("test error")
    Handle[IO, ApiError].attempt(error.raise[IO, String]).map { either =>
      assertEquals(either.left.map(_.message), Left("test error"))
    }
  }

}
