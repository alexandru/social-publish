package socialpublish.frontend.utils

fun isUnauthorized(response: ApiResponse<*>): Boolean =
    response is ApiResponse.Error && response.code == 401

fun buildLoginRedirectPath(currentPath: String, reason: String = "session_expired"): String {
    val encodedPath = js("encodeURIComponent")(currentPath) as String
    return "/login?reason=$reason&redirect=$encodedPath"
}
