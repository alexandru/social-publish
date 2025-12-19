package socialpublish.models

import munit.CatsEffectSuite
import socialpublish.models.*
import java.util.UUID

class ErrorsSpec extends CatsEffectSuite:
  
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
  
  test("Result.success creates successful result") {
    val result = Result.success("test value")
    result.value.map { either =>
      assertEquals(either, Right("test value"))
    }
  }
  
  test("Result.error creates error result") {
    val error = ApiError.validationError("test error")
    val result = Result.error[String](error)
    result.value.map { either =>
      assert(either.isLeft)
      assertEquals(either.left.toOption.get.message, "test error")
    }
  }
