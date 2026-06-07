package socialpublish.backend.clients.common

import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.ValidationError
import socialpublish.backend.db.UserSession

interface SocialMediaApi<Config> {
    fun validateRequest(request: NewPostRequest): ValidationError?

    context(_: UserSession)
    suspend fun createThread(
        config: Config,
        request: NewPostRequest,
    ): ApiResult<NewPostResponse>
}
