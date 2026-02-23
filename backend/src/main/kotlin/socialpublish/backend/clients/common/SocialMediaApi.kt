package socialpublish.backend.clients.common

import java.util.UUID
import socialpublish.backend.common.ApiResult
import socialpublish.backend.common.NewPostRequest
import socialpublish.backend.common.NewPostResponse
import socialpublish.backend.common.ValidationError

interface SocialMediaApi<Config> {
    fun validateRequest(request: NewPostRequest): ValidationError?

    suspend fun createThread(
        config: Config,
        request: NewPostRequest,
        userUuid: UUID,
    ): ApiResult<NewPostResponse>
}
