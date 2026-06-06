package socialpublish.backend.clients.common

import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.ValidationError
import socialpublish.backend.db.UUIDv7

interface SocialMediaApi<Config> {
    fun validateRequest(request: NewPostRequest): ValidationError?

    suspend fun createThread(
        config: Config,
        request: NewPostRequest,
        userUuid: UUIDv7,
    ): ApiResult<NewPostResponse>
}
